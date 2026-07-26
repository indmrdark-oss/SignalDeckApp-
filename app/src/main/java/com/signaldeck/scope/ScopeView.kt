package com.signaldeck.scope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
    private val gridPaint = Paint().apply { color = Color.argb(30, 77, 255, 160); strokeWidth = 1f }
    private val midlinePaint = Paint().apply { color = Color.argb(70, 77, 255, 160); strokeWidth = 1f }
    private val tracePaint = Paint().apply {
        color = Color.parseColor("#4DFFA0")
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.parseColor("#4DFFA0")
        textSize = 26f
        isAntiAlias = true
    }
    private val axisPaint = Paint().apply {
        color = Color.parseColor("#8FCBA6")
        textSize = 21f
        isAntiAlias = true
    }
    private val axisLinePaint = Paint().apply {
        color = Color.argb(120, 77, 255, 160)
        strokeWidth = 2f
    }
    private val zoomLabelPaint = Paint().apply {
        color = Color.parseColor("#FFB347")
        textSize = 22f
        isAntiAlias = true
    }
    private val badgeBgPaint = Paint().apply {
        color = Color.parseColor("#4DFFA0")
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint().apply {
        color = Color.parseColor("#050A06")
        textSize = 20f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
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

    private val leftMargin = 78f
    private val bottomMargin = 40f
    private val topMargin = 40f
    private val rightMargin = 10f

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
            val plotW = width.toFloat() - leftMargin - rightMargin
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
        if (mode == "captured" && zoomLevel > 1.01f) {
            canvas.drawText("ZOOM %.1fx".format(zoomLevel), plotRight - 130f, 24f, zoomLabelPaint)
        }

        if (mode == "captured" && capturedSamples != null) {
            drawCaptured(canvas, plotLeft, plotTop, plotW, plotH)
        } else {
            drawReconstructed(canvas, plotLeft, plotTop, plotW, plotH)
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

    private fun formatTime(ms: Double): String {
        return if (ms < 1.0) "%.0fus".format(ms * 1000.0)
        else if (ms < 10.0) "%.2fms".format(ms)
        else "%.1fms".format(ms)
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
                canvas.drawText("%.2fV".format(v), 2f, y + 8f, axisPaint)
            }

            val samples = capturedSamples
            if (samples != null && samples.isNotEmpty()) {
                val (winStart, winEnd) = visibleWindow(samples.size)
                if (winEnd > winStart) {
                    val lastVal = samples[winEnd - 1]
                    val normalized = (lastVal / 255.0)
                    val badgeY = top + h - (normalized.toFloat() * h)
                    val badgeRect = RectF(left - 40f, badgeY - 12f, left - 4f, badgeY + 12f)
                    canvas.drawRect(badgeRect, badgeBgPaint)
                    canvas.drawText("C1", badgeRect.centerX(), badgeRect.centerY() + 7f, badgeTextPaint)
                }
            }
        } else {
            canvas.drawText("HIGH", 2f, top + 14f, axisPaint)
            canvas.drawText("LOW", 2f, bottom - 4f, axisPaint)
            canvas.drawText("(no voltage", 2f, top + h / 2 - 6f, axisPaint)
            canvas.drawText("measurement)", 2f, top + h / 2 + 16f, axisPaint)
        }

        if (mode == "captured" && capturedSampleRateHz > 0 && capturedSamples != null) {
            val totalSamples = capturedSamples!!.size
            val (winStart, winEnd) = visibleWindow(totalSamples)
            val winSamples = (winEnd - winStart).coerceAtLeast(1)
            val startTimeMs = (winStart / capturedSampleRateHz) * 1000.0
            val windowTimeMs = (winSamples / capturedSampleRateHz) * 1000.0
            for (i in 0..cols) {
                val t = startTimeMs + (windowTimeMs / cols) * i
                val x = left + (w / cols) * i
                canvas.drawText(formatTime(t), x, bottom + 26f, axisPaint)
            }
            canvas.drawText("TIME (real, measured sample rate)", (left + right) / 2 - 120f, bottom + 38f, axisPaint)
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
        val (winStart, winEnd) = visibleWindow(samples.size)
        val count = winEnd - winStart
        if (count < 2) return

        var prevX = 0f; var prevY = 0f
        for (i in 0 until count) {
            val sampleIdx = winStart + i
            val x = left + (i.toFloat() / (count - 1)) * w
            val voltage = (samples[sampleIdx] / 255.0) * 5.0
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
