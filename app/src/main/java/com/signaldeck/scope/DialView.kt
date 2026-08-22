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

class DialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
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
    private var visualRotation = 0f
    private var touchingDial = false

    override fun onTouchEvent(event: MotionEvent): Boolean {

        val cx = width / 2f
        val cy = height / 2f

        val angle = Math.toDegrees(
            atan2(
                (event.y - cy).toDouble(),
                (event.x - cx).toDouble()
            )
        ).toFloat()

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                // Stop the ScrollView from stealing the gesture.
                parent.requestDisallowInterceptTouchEvent(true)

                touchingDial = true
                lastAngle = angle

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touchingDial) return true

                var delta = angle - lastAngle

                // Correct the jump when crossing -180/+180 degrees.
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f

                lastAngle = angle
                visualRotation += delta

                invalidate()

                onRotate?.invoke(delta)

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                touchingDial = false

                // Give scrolling back to the ScrollView.
                parent.requestDisallowInterceptTouchEvent(false)

                performClick()

                return true
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {

        val cx = width / 2f
        val cy = height / 2f

        val radius = (minOf(width, height) / 2f) - 12f

        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx, cy, radius, ringBorderPaint)

        // 36 tick marks.
        for (i in 0 until 36) {

            val a = Math.toRadians((i * 10).toDouble())

            val r1 = radius - 14f
            val r2 = radius - 4f

            val x1 = cx + (r1 * cos(a)).toFloat()
            val y1 = cy + (r1 * sin(a)).toFloat()

            val x2 = cx + (r2 * cos(a)).toFloat()
            val y2 = cy + (r2 * sin(a)).toFloat()

            canvas.drawLine(
                x1,
                y1,
                x2,
                y2,
                tickPaint
            )
        }

        // Rotation indicator.
        val a = Math.toRadians(visualRotation.toDouble())

        val ix = cx + (radius - 30f) * cos(a).toFloat()
        val iy = cy + (radius - 30f) * sin(a).toFloat()

        canvas.drawLine(
            cx,
            cy,
            ix,
            iy,
            indicatorPaint
        )

        canvas.drawCircle(
            cx,
            cy,
            14f,
            centerPaint
        )
    }
}
