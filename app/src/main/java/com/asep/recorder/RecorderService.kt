package com.asep.recorder

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.*
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecorderService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    // Floating overlay
    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var isPaused = false

    companion object {
        const val CHANNEL_ID = "recorder_channel"
        const val NOTIF_ID = 88
        const val ACTION_START  = "start"
        const val ACTION_STOP   = "stop"
        const val ACTION_PAUSE  = "pause"
        const val ACTION_RESUME = "resume"
        const val PREFS         = "recorder_prefs"
        const val KEY_FPS       = "fps"
        const val KEY_MIC       = "mic_enabled"
        const val KEY_MIC_VOL   = "mic_volume"   // 0-100
        const val KEY_APP_VOL   = "app_volume"   // 0-100
        var isRecording = false
        var isPausedStatic = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra("resultCode", -1)
                val data = intent.getParcelableExtra<Intent>("data")
                if (data != null) startRecording(resultCode, data)
            }
            ACTION_STOP   -> stopRecording()
            ACTION_PAUSE  -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val micEnabled = prefs.getBoolean(KEY_MIC, true)
        val micVolPct  = prefs.getInt(KEY_MIC_VOL, 100)
        val appVolPct  = prefs.getInt(KEY_APP_VOL, 100)

        // Apply app (media) volume
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVol = (maxVol * appVolPct / 100f).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)

        val projMgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projMgr.getMediaProjection(resultCode, data)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        // Output folder: /sdcard/asep_recorder
        val outDir = File(
            Environment.getExternalStorageDirectory(),
            "asep_recorder"
        ).also { it.mkdirs() }

        val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "REC_$ts.mp4"
        outputFile = File(outDir, name)

        val fps = prefs.getInt(KEY_FPS, 30)

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }.apply {
            // Audio
            if (micEnabled) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (micEnabled) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128_000)
                // Mic volume via input gain isn't directly settable on MediaRecorder,
                // but we apply it via AudioManager MIC gain workaround
                val micMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val micTarget = (micMax * micVolPct / 100f).toInt()
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, micTarget, 0)
            }
            setVideoSize(w, h)
            setVideoFrameRate(fps)
            setVideoEncodingBitRate(10 * 1024 * 1024)
            setOutputFile(outputFile!!.absolutePath)
            prepare()
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AsepRecorder", w, h, dpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder!!.surface, null, null
        )

        mediaRecorder?.start()
        isRecording = true
        isPausedStatic = false

        showFloatingControl()
    }

    private fun pauseRecording() {
        if (!isRecording || isPaused) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder?.pause()
            isPaused = true
            isPausedStatic = true
        }
    }

    private fun resumeRecording() {
        if (!isRecording || !isPaused) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder?.resume()
            isPaused = false
            isPausedStatic = false
        }
    }

    private fun stopRecording() {
        removeFloatingControl()
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop()  } catch (_: Exception) {}
        mediaRecorder   = null
        virtualDisplay  = null
        mediaProjection = null
        isRecording     = false
        isPausedStatic  = false
        isPaused        = false

        // Announce to MediaStore
        outputFile?.let { f ->
            if (f.exists()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.DATA, f.absolutePath)
                    }
                    contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                } else {
                    sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        android.net.Uri.fromFile(f)))
                }
            }
        }

        stopForeground(true)
        stopSelf()
    }

    // ── Floating overlay ──────────────────────────────────────────────────────

    private fun showFloatingControl() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(this)
        floatView = inflater.inflate(R.layout.overlay_controls, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50; y = 200
        }

        // Drag
        var startX = 0; var startY = 0; var initX = 0; var initY = 0
        floatView?.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX.toInt(); startY = e.rawY.toInt()
                    initX = params.x;       initY = params.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (e.rawX.toInt() - startX)
                    params.y = initY + (e.rawY.toInt() - startY)
                    windowManager?.updateViewLayout(floatView, params)
                    true
                }
                else -> false
            }
        }

        val btnStop  = floatView?.findViewById<ImageButton>(R.id.overlay_stop)
        val btnPause = floatView?.findViewById<ImageButton>(R.id.overlay_pause)

        btnStop?.setOnClickListener  { stopRecording() }
        btnPause?.setOnClickListener {
            if (isPaused) {
                resumeRecording()
                btnPause.setImageResource(android.R.drawable.ic_media_pause)
            } else {
                pauseRecording()
                btnPause.setImageResource(android.R.drawable.ic_media_play)
            }
        }

        windowManager?.addView(floatView, params)
    }

    private fun removeFloatingControl() {
        try { windowManager?.removeView(floatView) } catch (_: Exception) {}
        floatView = null
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Screen Recorder",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Asep Recorder")
            .setContentText("Recording in progress…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
    }
}
