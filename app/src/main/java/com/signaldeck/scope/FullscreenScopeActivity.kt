package com.signaldeck.scope

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class FullscreenScopeActivity : AppCompatActivity() {

    private lateinit var scopeView: ScopeView
    private val handler = Handler(Looper.getMainLooper())
    private var lastMode = ""

    private val poller = object : Runnable {
        override fun run() {
            scopeView.liveFreq = ScopeDataHub.liveFreq
            scopeView.liveDuty = ScopeDataHub.liveDuty
            scopeView.waveformPresent = ScopeDataHub.waveformPresent

            if (ScopeDataHub.mode == "captured" && ScopeDataHub.capturedSamples != null) {
                if (lastMode != "captured" || scopeView.capturedSamples !== ScopeDataHub.capturedSamples) {
                    scopeView.showCaptured(
                        ScopeDataHub.capturedSamples!!,
                        ScopeDataHub.fidelityLabel,
                        ScopeDataHub.capturedSampleRateHz
                    )
                }
                lastMode = "captured"
            } else {
                if (lastMode != "reconstructed") scopeView.showReconstructed()
                lastMode = "reconstructed"
            }
            handler.postDelayed(this, 150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_fullscreen_scope)

        scopeView = findViewById(R.id.fsScopeView)
        val backBtn: TextView = findViewById(R.id.backBtn)
        val statusText: TextView = findViewById(R.id.statusText)
        val zoomInBtn: Button = findViewById(R.id.fsZoomInBtn)
        val zoomOutBtn: Button = findViewById(R.id.fsZoomOutBtn)
        val resetBtn: Button = findViewById(R.id.fsResetBtn)

        statusText.text = if (ScopeDataHub.connected) "Live" else "Not connected - go back and connect first"

        backBtn.setOnClickListener { finish() }
        zoomInBtn.setOnClickListener { scopeView.zoomIn() }
        zoomOutBtn.setOnClickListener { scopeView.zoomOut() }
        resetBtn.setOnClickListener { scopeView.resetZoom() }

        handler.post(poller)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(poller)
    }
}
