package com.signaldeck.scope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class ProScopeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint().apply { color = Color.parseColor("#050A06") }
    private val gridPaint = Paint().apply { color = Color.argb(30, 77, 255, 160); strokeWidth = 1f }
    private val tracePaint = Paint().apply {
        color = Color.parseColor("#4DFFA0")
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val axisPaint = Paint().apply {
        color = Color.parseColor("#8FCBA6")
        textSize = 20f
        isAntiAlias = true
    }
    private val cursorPaint = Paint().apply {
        color = Color.parseColor("#FFB347")
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val cursorHandlePaint = Paint().apply {
        color = Color.parseColor("#FFB347")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    var samples: IntArray? = null
    var sampleRateHz: Double = 0.0
    var onCursorMoved: (() -> Unit)? = null

    private val leftMargin = 60f
    private val bottomMargin = 34f
    private val topMargin = 20f
    private val rightMargin = 10f

    private var cursor1Frac = 0.3f
    private var cursor2Frac = 0.7f
    private var draggingCursor = 0

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val plotLeft = leftMargin
        val plotRight = width - rightMargin
        val plotW = plotRight - plotLeft
        if (plotW <= 0) return true

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val touchFrac = ((event.x - plotLeft) / plotW).coerceIn(0f, 1f)
                val d1 = abs(touchFrac - cursor1Frac)
                val d2 = abs(touchFrac - cursor2Frac)
                draggingCursor = if (d1 < d2) 1 else 2
            }
            MotionEvent.ACTION_MOVE -> {
                val touchFrac = ((event.x - plotLeft) / plotW).coerceIn(0f, 1f)
                if (draggingCursor == 1) cursor1Frac = touchFrac
                else if (draggingCursor == 2) cursor2Frac = touchFrac
                invalidate()
                onCursorMoved?.invoke()
            }
            MotionEvent.ACTION_UP -> draggingCursor = 0
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val fullW = width.toFloat()
        val fullH = height.toFloat()
        canvas.drawRect(0f, 0f, fullW, fullH, bgPaint)

        val left = leftMargin
        val top = topMargin
        val right = fullW - rightMargin
        val bottom = fullH - bottomMargin
        val w = right - left
        val h = bottom - top

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

        val voltsPerRow = 5.0f / rows
        for (i in 0..rows) {
            val v = 5.0f - (voltsPerRow * i)
            val y = top + (h / rows) * i
            canvas.drawText("%.2fV".format(v), 2f, y + 7f, axisPaint)
        }

        val samp = samples
        if (samp != null && samp.isNotEmpty()) {
            var prevX = 0f; var prevY = 0f
            for (i in samp.indices) {
                val x = left + (i.toFloat() / (samp.size - 1)) * w
                val v = (samp[i] / 255.0).toFloat()
                val y = top + h - (v * h)
                if (i > 0) canvas.drawLine(prevX, prevY, x, y, tracePaint)
                prevX = x; prevY = y
            }
        }

        val c1x = left + cursor1Frac * w
        val c2x = left + cursor2Frac * w
        canvas.drawLine(c1x, top, c1x, bottom, cursorPaint)
        canvas.drawLine(c2x, top, c2x, bottom, cursorPaint)
        canvas.drawCircle(c1x, top + 10f, 10f, cursorHandlePaint)
        canvas.drawCircle(c2x, top + 10f, 10f, cursorHandlePaint)
    }

    fun measurements(): String {
        val samp = samples ?: return "No captured samples yet - start Live Capture first."
        if (samp.isEmpty() || sampleRateHz <= 0) return "No captured samples yet - start Live Capture first."

        val idx1 = (cursor1Frac * (samp.size - 1)).toInt().coerceIn(0, samp.size - 1)
        val idx2 = (cursor2Frac * (samp.size - 1)).toInt().coerceIn(0, samp.size - 1)
        val t1 = (idx1 / sampleRateHz) * 1000.0
        val t2 = (idx2 / sampleRateHz) * 1000.0
        val v1 = (samp[idx1] / 255.0) * 5.0
        val v2 = (samp[idx2] / 255.0) * 5.0
        val dt = abs(t2 - t1)
        val dv = abs(v2 - v1)
        val freqFromCursors = if (dt > 0) 1000.0 / dt else 0.0

        return "C1: %.3fms, %.2fV  |  C2: %.3fms, %.2fV\nΔT=%.3fms  ΔV=%.2fV  1/ΔT=%.1fHz"
            .format(t1, v1, t2, v2, dt, dv, freqFromCursors)
    }
}
