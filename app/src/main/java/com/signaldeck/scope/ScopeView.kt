package com.signaldeck.scope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
    private val zoomLabelPaint = Paint().apply {
        color = Color.parseColor("#FFB347")
        textSize = 22f
        isAntiAlias = true
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

    private var zoomLevel = 1f
    private val minZoom = 1f
    private val maxZoom = 20f
    private var panCenter = 0.5f

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

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (mode != "captured") return false
            zoomLevel = (zoomLevel * detector.scaleFactor).coerceIn(minZoom, maxZoom)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (mode != "captured") return false
            val plotW = width.toFloat()
            if (plotW <= 0) return false
            val windowFraction = 1f / zoomLevel
            val deltaFraction = (dx / plotW) * windowFraction
            panCenter = (panCenter + deltaFraction).coerceIn(windowFraction / 2f, 1f - windowFraction / 2f)
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val cols = 12; val rows = 6
        for (i in 0..cols) {
            val x = w / cols * i
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }
        for (i in 0..rows) {
            val y = h / rows * i
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        canvas.drawLine(0f, h / 2, w, h / 2, midlinePaint)

        canvas.drawText(fidelityLabel, 20f, 24f, labelPaint)
        if (mode == "captured" && zoomLevel > 1.01f) {
            canvas.drawText("ZOOM %.1fx".format(zoomLevel), w - 130f, 24f, zoomLabelPaint)
        }

        if (mode == "captured" && capturedSamples != null) {
            drawCaptured(canvas, w, h)
        } else {
            drawReconstructed(canvas, w, h)
        }
    }

    private fun visibleWindow(totalSamples: Int): Pair<Int, Int> {
        if (totalSamples == 0) return 0 to 0
        val windowSize = (totalSamples / zoomLevel).toInt().coerceAtLeast(2)
        val centerIdx = (panCenter * totalSamples).toInt()
        var start = centerIdx - windowSize / 2
        var end = start + windowSize
        if (start < 0) { end -= start; start = 0 }
        if (end > totalSamples) { start -= (end - totalSamples); end = totalSamples }
        start = start.coerceAtLeast(0)
        return start to end
    }

    private fun drawCaptured(canvas: Canvas, w: Float, h: Float) {
        val samples = capturedSamples ?: return
        if (samples.isEmpty()) return
        val (winStart, winEnd) = visibleWindow(samples.size)
        val count = winEnd - winStart
        if (count < 2) return
        var prevX = 0f; var prevY = 0f
        for (i in 0 until count) {
            val sampleIdx = winStart + i
            val x = (i.toFloat() / (count - 1)) * w
            val v = samples[sampleIdx] / 255f
            val y = h - (v * (h * 0.82f) + h * 0.09f)
            if (i > 0) canvas.drawLine(prevX, prevY, x, y, tracePaint)
            prevX = x; prevY = y
        }
    }

    private fun drawReconstructed(canvas: Canvas, w: Float, h: Float) {
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
            val x = (i.toFloat() / steps) * w
            val y = if (high) h * 0.18f else h * 0.82f
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

    fun zoomIn() {
        if (mode != "captured") return
        zoomLevel = (zoomLevel * 1.5f).coerceIn(minZoom, maxZoom)
        invalidate()
    }

    fun zoomOut() {
        if (mode != "captured") return
        zoomLevel = (zoomLevel / 1.5f).coerceIn(minZoom, maxZoom)
        invalidate()
    }

    fun resetZoom() {
        zoomLevel = minZoom
        panCenter = 0.5f
        invalidate()
    }
}
