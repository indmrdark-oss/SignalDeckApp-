package com.signaldeck.scope

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ProScopeActivity : AppCompatActivity() {

    private lateinit var proScopeView: ProScopeView
    private lateinit var cursorReadout: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val poller = object : Runnable {
        override fun run() {
            if (ScopeDataHub.capturedSamples != null) {
                proScopeView.samples = ScopeDataHub.capturedSamples
                proScopeView.sampleRateHz = ScopeDataHub.capturedSampleRateHz
                proScopeView.invalidate()
                cursorReadout.text = proScopeView.measurements()
            } else {
                cursorReadout.text = "No captured samples yet - go back, tap Connect and Start Live Capture."
            }
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pro_scope)

        proScopeView = findViewById(R.id.proScopeView)
        cursorReadout = findViewById(R.id.cursorReadout)
        val backBtn: TextView = findViewById(R.id.backBtn)
        val resetCursorsBtn: Button = findViewById(R.id.resetCursorsBtn)

        backBtn.setOnClickListener { finish() }
        resetCursorsBtn.setOnClickListener {
            proScopeView.invalidate()
        }

        proScopeView.onCursorMoved = { cursorReadout.text = proScopeView.measurements() }

        handler.post(poller)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(poller)
    }
}
