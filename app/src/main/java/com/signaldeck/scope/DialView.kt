package com.signaldeck.scope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A relative rotary encoder, not an absolute-position dial.
 * Dragging around it reports ANGLE DELTA to a callback - the app
 * decides how much frequency that delta represents (coarse/fine mode).
 * This is the only reliable way to get genuine 1Hz precision across
 * a 1-20000Hz range from a touchscreen; an absolute sweep dial cannot
 * physically resolve that.
 */
class DialView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val ringPaint = Paint().apply {
        color = Color.parseColor("#0D150E")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val ringBorderPaint = Paint().apply {
        color = Color.parseColor("#4DFFA0")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val tickPaint = Paint().apply {
        color = Color.argb(90, 77, 255, 160)
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val indicatorPaint = Paint().apply {
        color = Color.parseColor("#4DFFA0")
        strokeWidth = 8f
        isAntiAlias = true
    }
    private val centerPaint = Paint().apply {
        color = Color.parseColor("#4DFFA0")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    var onRotate: ((deltaDegrees: Float) -> Unit)? = null

    private var lastAngle = 0f
    private var visualRotation = 0f  // purely cosmetic spin, not tied to frequency value

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val angle = Math.toDegrees(atan2((event.y - cy).toDouble(), (event.x - cx).toDouble())).toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastAngle = angle
            }
            MotionEvent.ACTION_MOVE -> {
                var delta = angle - lastAngle
                // handle wraparound at +-180
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f
                lastAngle = angle
                visualRotation += delta
                invalidate()
                onRotate?.invoke(delta)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) - 12f

        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx, cy, radius, ringBorderPaint)

        // tick marks around the ring
        for (i in 0 until 36) {
            val a = Math.toRadians((i * 10).toDouble())
            val r1 = radius - 14f
            val r2 = radius - 4f
            val x1 = cx + (r1 * cos(a)).toFloat()
            val y1 = cy + (r1 * sin(a)).toFloat()
            val x2 = cx + (r2 * cos(a)).toFloat()
            val y2 = cy + (r2 * sin(a)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // spinning indicator line - shows rotation activity, not an absolute value
        val a = Math.toRadians(visualRotation.toDouble())
        val ix = cx + (radius - 30f) * cos(a).toFloat()
        val iy = cy + (radius - 30f) * sin(a).toFloat()
        canvas.drawLine(cx, cy, ix, iy, indicatorPaint)
        canvas.drawCircle(cx, cy, 14f, centerPaint)
    }
}
