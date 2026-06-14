package com.asep.recorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var fpsSlider: SeekBar
    private lateinit var fpsLabel: TextView
    private lateinit var prefs: android.content.SharedPreferences

    private var elapsedSeconds = 0
    private var timerHandler: Handler? = null
    private var timerRunnable: Runnable? = null

    companion object {
        const val REQ_SCREEN_CAPTURE = 1
        const val REQ_PERMS = 2
        const val MIN_FPS = 10
        const val MAX_FPS = 90
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        timerText = findViewById(R.id.timer_text)
        startButton = findViewById(R.id.btn_start)
        stopButton = findViewById(R.id.btn_stop)
        fpsSlider = findViewById(R.id.fps_slider)
        fpsLabel = findViewById(R.id.fps_label)

        prefs = getSharedPreferences(RecorderService.PREFS, Context.MODE_PRIVATE)

        val savedFps = prefs.getInt(RecorderService.KEY_FPS, 30).coerceIn(MIN_FPS, MAX_FPS)
        fpsSlider.max = MAX_FPS - MIN_FPS
        fpsSlider.progress = savedFps - MIN_FPS
        fpsLabel.text = "FPS: $savedFps"

        fpsSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val fps = progress + MIN_FPS
                fpsLabel.text = "FPS: $fps"
                prefs.edit().putInt(RecorderService.KEY_FPS, fps).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        updateUI()

        startButton.setOnClickListener {
            checkPermissionsAndStart()
        }

        stopButton.setOnClickListener {
            stopRecording()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        if (RecorderService.isRecording) startTimer() else stopTimer()
    }

    private fun updateUI() {
        val fpsContainer = findViewById<android.view.View>(R.id.fps_container)
        if (RecorderService.isRecording) {
            statusText.text = "Recording..."
            startButton.visibility = android.view.View.GONE
            stopButton.visibility = android.view.View.VISIBLE
            fpsContainer.visibility = android.view.View.GONE
        } else {
            statusText.text = "Ready to record"
            startButton.visibility = android.view.View.VISIBLE
            stopButton.visibility = android.view.View.GONE
            timerText.text = "00:00"
            elapsedSeconds = 0
            fpsContainer.visibility = android.view.View.VISIBLE
        }
    }

    private fun checkPermissionsAndStart() {
        val perms = mutableListOf<String>()
        perms.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), REQ_PERMS)
        } else {
            requestScreenCapture()
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (req == REQ_PERMS && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            requestScreenCapture()
        } else if (req == REQ_PERMS) {
            Toast.makeText(this, "Permissions needed!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
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
        } else if (requestCode == REQ_SCREEN_CAPTURE) {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        val intent = Intent(this, RecorderService::class.java).apply {
            action = RecorderService.ACTION_STOP
        }
        startService(intent)
        stopTimer()
        updateUI()
        Toast.makeText(this, "Recording saved to Movies/AsepRecorder", Toast.LENGTH_LONG).show()
    }

    private fun startTimer() {
        timerHandler = Handler(Looper.getMainLooper())
        timerRunnable = object : Runnable {
            override fun run() {
                elapsedSeconds++
                val min = elapsedSeconds / 60
                val sec = elapsedSeconds % 60
                timerText.text = String.format("%02d:%02d", min, sec)
                timerHandler?.postDelayed(this, 1000)
            }
        }
        timerHandler?.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler?.removeCallbacks(it) }
    }
}
