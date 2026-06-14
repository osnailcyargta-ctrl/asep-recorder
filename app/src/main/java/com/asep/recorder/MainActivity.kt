package com.asep.recorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // Audio settings
    private lateinit var switchMic: Switch
    private lateinit var switchAppSound: Switch
    private lateinit var sliderMicVol: SeekBar
    private lateinit var sliderAppVol: SeekBar
    private lateinit var labelMicVol: TextView
    private lateinit var labelAppVol: TextView
    private lateinit var rowMicVol: LinearLayout

    private lateinit var fpsSlider: SeekBar
    private lateinit var fpsLabel: TextView

    private lateinit var prefs: android.content.SharedPreferences

    private var elapsedSeconds = 0
    private var timerHandler: Handler? = null
    private var timerRunnable: Runnable? = null

    companion object {
        const val REQ_SCREEN_CAPTURE = 1
        const val REQ_PERMS = 2
        const val REQ_OVERLAY = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(RecorderService.PREFS, Context.MODE_PRIVATE)

        // Bind views
        statusText    = findViewById(R.id.status_text)
        timerText     = findViewById(R.id.timer_text)
        btnStart      = findViewById(R.id.btn_start)
        btnStop       = findViewById(R.id.btn_stop)
        switchMic     = findViewById(R.id.switch_mic)
        switchAppSound= findViewById(R.id.switch_app_sound)
        sliderMicVol  = findViewById(R.id.slider_mic_vol)
        sliderAppVol  = findViewById(R.id.slider_app_vol)
        labelMicVol   = findViewById(R.id.label_mic_vol)
        labelAppVol   = findViewById(R.id.label_app_vol)
        rowMicVol     = findViewById(R.id.row_mic_vol)
        fpsSlider     = findViewById(R.id.fps_slider)
        fpsLabel      = findViewById(R.id.fps_label)

        // Load prefs
        switchMic.isChecked      = prefs.getBoolean(RecorderService.KEY_MIC, true)
        switchAppSound.isChecked = prefs.getInt(RecorderService.KEY_APP_VOL, 100) > 0
        sliderMicVol.progress    = prefs.getInt(RecorderService.KEY_MIC_VOL, 100)
        sliderAppVol.progress    = prefs.getInt(RecorderService.KEY_APP_VOL, 100)
        fpsSlider.progress       = prefs.getInt(RecorderService.KEY_FPS, 30) - 10

        updateMicVolVisibility()
        updateLabels()

        // Mic toggle
        switchMic.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(RecorderService.KEY_MIC, checked).apply()
            updateMicVolVisibility()
        }

        // App sound toggle (just sets vol to 0 or restores)
        switchAppSound.setOnCheckedChangeListener { _, checked ->
            val vol = if (checked) sliderAppVol.progress.coerceAtLeast(10) else 0
            prefs.edit().putInt(RecorderService.KEY_APP_VOL, vol).apply()
        }

        // Mic volume slider
        sliderMicVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                labelMicVol.text = "Mic Volume: $p%"
                prefs.edit().putInt(RecorderService.KEY_MIC_VOL, p).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // App volume slider
        sliderAppVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                labelAppVol.text = "App Volume: $p%"
                if (switchAppSound.isChecked)
                    prefs.edit().putInt(RecorderService.KEY_APP_VOL, p).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // FPS slider (10–90)
        fpsSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val fps = p + 10
                fpsLabel.text = "FPS: $fps"
                prefs.edit().putInt(RecorderService.KEY_FPS, fps).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Create output folder immediately on launch
        val outDir = File(Environment.getExternalStorageDirectory(), "asep_recorder")
        if (!outDir.exists()) outDir.mkdirs()

        btnStart.setOnClickListener { checkAndStart() }
        btnStop.setOnClickListener  { stopRecording() }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        if (RecorderService.isRecording) startTimer() else stopTimer()
    }

    private fun updateMicVolVisibility() {
        rowMicVol.visibility = if (switchMic.isChecked)
            android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun updateLabels() {
        labelMicVol.text = "Mic Volume: ${sliderMicVol.progress}%"
        labelAppVol.text = "App Volume: ${sliderAppVol.progress}%"
        fpsLabel.text    = "FPS: ${fpsSlider.progress + 10}"
    }

    private fun updateUI() {
        val recording = RecorderService.isRecording
        statusText.text = when {
            RecorderService.isPausedStatic -> "Paused"
            recording -> "Recording…"
            else -> "Ready to record"
        }
        btnStart.visibility = if (recording) android.view.View.GONE else android.view.View.VISIBLE
        btnStop.visibility  = if (recording) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.settings_card).visibility =
            if (recording) android.view.View.GONE else android.view.View.VISIBLE
    }

    // ── Permission flow ───────────────────────────────────────────────────────

    private fun checkAndStart() {
        // 1. Overlay permission (needed for floating control)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            return
        }
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (switchMic.isChecked)
            needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        } else {
            requestScreenCapture()
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (req == REQ_PERMS) {
            if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
                requestScreenCapture()
            } else {
                Toast.makeText(this, "Permission needed!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestScreenCapture() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    Settings.canDrawOverlays(this)) {
                    checkPermissionsAndStart()
                } else {
                    Toast.makeText(this,
                        "Overlay permission required for floating control",
                        Toast.LENGTH_SHORT).show()
                }
            }
            REQ_SCREEN_CAPTURE -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        val intent = Intent(this, RecorderService::class.java).apply {
                            action = RecorderService.ACTION_START
                            putExtra("resultCode", resultCode)
                            putExtra("data", data)
                        }
                        startForegroundService(intent)
                        updateUI()
                        startTimer()
                        Toast.makeText(this, "Recording started!", Toast.LENGTH_SHORT).show()
                        moveTaskToBack(true)
                    }, 300)
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun stopRecording() {
        startService(Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_STOP
        })
        stopTimer()
        updateUI()
        Toast.makeText(this, "Saved to /sdcard/asep_recorder/", Toast.LENGTH_LONG).show()
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun startTimer() {
        timerHandler = Handler(Looper.getMainLooper())
        timerRunnable = object : Runnable {
            override fun run() {
                if (!RecorderService.isPausedStatic) elapsedSeconds++
                val m = elapsedSeconds / 60
                val s = elapsedSeconds % 60
                timerText.text = String.format("%02d:%02d", m, s)
                timerHandler?.postDelayed(this, 1000)
            }
        }
        timerHandler?.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler?.removeCallbacks(it) }
        elapsedSeconds = 0
        timerText.text = "00:00"
    }
}
