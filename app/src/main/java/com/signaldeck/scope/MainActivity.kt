package com.signaldeck.scope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

class ScopeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint().apply { color = Color.parseColor("#050A06") }
    private val gridPaint = Paint().apply { color = Color.argb(25, 77, 255, 160); strokeWidth = 1f }
    private val midlinePaint = Paint().apply { color = Color.argb(55, 77, 255, 160); strokeWidth = 1f }
    private val tracePaint = Paint().apply {
        color = Color.parseColor("#4DFFA0")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.parseColor("#4DFFA0")
        textSize = 28f
        isAntiAlias = true
    }
    private val axisPaint = Paint().apply {
        color = Color.parseColor("#6F9A80")
        textSize = 22f
        isAntiAlias = true
    }
    private val axisLinePaint = Paint().apply {
        color = Color.argb(80, 111, 154, 128)
        strokeWidth = 2f
    }

    var liveFreq: Double = 0.0
    var liveDuty: Double = 50.0
    var waveformPresent: Boolean = false
    private var phase = 0.0
    private var lastFrameNanos = System.nanoTime()

    var mode: String = "reconstructed"
    var capturedSamples: IntArray? = null
    var fidelityLabel: String = "RECONSTRUCTED"
    var capturedSampleRateHz: Double = 0.0

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (mode == "reconstructed") invalidate()
            handler.postDelayed(this, 30)
        }
    }

    init {
        handler.post(tick)
    }

    private val leftMargin = 70f
    private val bottomMargin = 40f
    private val topMargin = 40f
    private val rightMargin = 10f

    override fun onDraw(canvas: Canvas) {
        val fullW = width.toFloat()
        val fullH = height.toFloat()
        canvas.drawRect(0f, 0f, fullW, fullH, bgPaint)

        val plotLeft = leftMargin
        val plotTop = topMargin
        val plotRight = fullW - rightMargin
        val plotBottom = fullH - bottomMargin
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

        drawGridAndAxes(canvas, plotLeft, plotTop, plotRight, plotBottom, plotW, plotH)

        canvas.drawText(fidelityLabel, plotLeft, 24f, labelPaint)

        if (mode == "captured" && capturedSamples != null) {
            drawCaptured(canvas, plotLeft, plotTop, plotW, plotH)
        } else {
            drawReconstructed(canvas, plotLeft, plotTop, plotW, plotH)
        }
    }

    private fun drawGridAndAxes(
        canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, w: Float, h: Float
    ) {
        val cols = 10
        val rows = 8

        for (i in 0..cols) {
            val x = left + (w / cols) * i
            canvas.drawLine(x, top, x, bottom, gridPaint)
        }
        for (i in 0..rows) {
            val y = top + (h / rows) * i
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        canvas.drawLine(left, top + h / 2, right, top + h / 2, midlinePaint)

        canvas.drawLine(left, top, left, bottom, axisLinePaint)
        canvas.drawLine(left, bottom, right, bottom, axisLinePaint)

        if (mode == "captured" && capturedSamples != null) {
            val voltsPerRow = 5.0f / rows
            for (i in 0..rows) {
                val v = 5.0f - (voltsPerRow * i)
                val y = top + (h / rows) * i
                canvas.drawText("%.1fV".format(v), 4f, y + 8f, axisPaint)
            }
        } else {
            canvas.drawText("HIGH", 4f, top + 14f, axisPaint)
            canvas.drawText("LOW", 4f, bottom - 4f, axisPaint)
            canvas.drawText("(no voltage", 4f, top + h / 2 - 6f, axisPaint)
            canvas.drawText("measurement)", 4f, top + h / 2 + 16f, axisPaint)
        }

        if (mode == "captured" && capturedSampleRateHz > 0 && capturedSamples != null) {
            val totalSamples = capturedSamples!!.size
            val totalTimeMs = (totalSamples / capturedSampleRateHz) * 1000.0
            for (i in 0..cols) {
                val t = (totalTimeMs / cols) * i
                val x = left + (w / cols) * i
                val label = if (totalTimeMs < 2.0) "%.2fms".format(t) else "%.1fms".format(t)
                canvas.drawText(label, x, bottom + 26f, axisPaint)
            }
            canvas.drawText("TIME (real, from measured sample rate)", (left + right) / 2 - 130f, bottom + 38f, axisPaint)
        } else {
            for (i in 0..cols) {
                val x = left + (w / cols) * i
                val cyclesLabel = "%.1f".format((i.toDouble() / cols) * 4.0)
                canvas.drawText(cyclesLabel, x, bottom + 26f, axisPaint)
            }
            canvas.drawText("CYCLES (synthetic - no real timebase)", (left + right) / 2 - 110f, bottom + 38f, axisPaint)
        }
    }

    private fun drawCaptured(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        val samples = capturedSamples ?: return
        if (samples.isEmpty()) return
        var prevX = 0f; var prevY = 0f
        for (i in samples.indices) {
            val x = left + (i.toFloat() / (samples.size - 1)) * w
            val voltage = (samples[i] / 255.0) * 5.0
            val v = voltage / 5.0
            val y = top + h - (v.toFloat() * h)
            if (i > 0) canvas.drawLine(prevX, prevY, x, y, tracePaint)
            prevX = x; prevY = y
        }
    }

    private fun drawReconstructed(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        val now = System.nanoTime()
        val dt = (now - lastFrameNanos) / 1_000_000_000.0
        lastFrameNanos = now

        val freq = liveFreq
        if (freq <= 0) return
        val duty = (liveDuty / 100.0).coerceIn(0.01, 0.99)

        phase += dt * freq
        val cyclesShown = 4.0
        val steps = 400
        var prevX = 0f; var prevY = 0f
        for (i in 0..steps) {
            val t = (i.toDouble() / steps) * cyclesShown
            val localPhase = (t + phase) % 1.0
            val high = localPhase < duty
            val x = left + (i.toFloat() / steps) * w
            val y = if (high) top + h * 0.05f else top + h * 0.95f
            if (i > 0) canvas.drawLine(prevX, prevY, x, y, tracePaint)
            prevX = x; prevY = y
        }
    }

    fun showCaptured(samples: IntArray, label: String, sampleRateHz: Double) {
        capturedSamples = samples
        capturedSampleRateHz = sampleRateHz
        fidelityLabel = label
        mode = "captured"
        invalidate()
    }

    fun showReconstructed() {
        mode = "reconstructed"
        fidelityLabel = "RECONSTRUCTED · LIVE"
        invalidate()
    }
}
