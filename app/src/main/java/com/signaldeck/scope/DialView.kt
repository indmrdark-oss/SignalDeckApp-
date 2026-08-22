package com.signaldeck.scope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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

    // Fixed 1 Hz reference marker
    private val referencePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val referenceTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    var onRotate: ((deltaDegrees: Float) -> Unit)? = null

    private var lastAngle = 0f
    private var visualRotation = 0f

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

                lastAngle = angle

                // Prevent the ScrollView from stealing the dial gesture.
                parent?.requestDisallowInterceptTouchEvent(true)

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                var delta = angle - lastAngle

                // Handle crossing the -180/+180 boundary.
                if (delta > 180f) {
                    delta -= 360f
                }

                if (delta < -180f) {
                    delta += 360f
                }

                lastAngle = angle

                visualRotation += delta

                invalidate()

                onRotate?.invoke(delta)

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

                parent?.requestDisallowInterceptTouchEvent(false)

                return true
            }
        }

        return true
    }

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        val radius =
            (minOf(width, height) / 2f) - 12f

        // =====================================================
        // MAIN DIAL
        // =====================================================

        canvas.drawCircle(
            cx,
            cy,
            radius,
            ringPaint
        )

        canvas.drawCircle(
            cx,
            cy,
            radius,
            ringBorderPaint
        )

        // =====================================================
        // TICK MARKS
        // =====================================================

        for (i in 0 until 36) {

            val a = Math.toRadians(
                (i * 10).toDouble()
            )

            val r1 = radius - 14f
            val r2 = radius - 4f

            val x1 =
                cx + (r1 * cos(a)).toFloat()

            val y1 =
                cy + (r1 * sin(a)).toFloat()

            val x2 =
                cx + (r2 * cos(a)).toFloat()

            val y2 =
                cy + (r2 * sin(a)).toFloat()

            canvas.drawLine(
                x1,
                y1,
                x2,
                y2,
                tickPaint
            )
        }

        // =====================================================
        // FIXED 1 Hz REFERENCE
        // =====================================================

        // The marker is always at the top.
        // It does NOT rotate.

        val refAngle =
            Math.toRadians(-90.0)

        val refInner =
            radius - 20f

        val refOuter =
            radius + 2f

        val refX1 =
            cx +
                    (refInner * cos(refAngle)).toFloat()

        val refY1 =
            cy +
                    (refInner * sin(refAngle)).toFloat()

        val refX2 =
            cx +
                    (refOuter * cos(refAngle)).toFloat()

        val refY2 =
            cy +
                    (refOuter * sin(refAngle)).toFloat()

        canvas.drawLine(
            refX1,
            refY1,
            refX2,
            refY2,
            referencePaint
        )

        canvas.drawText(
            "1 Hz",
            cx,
            cy - radius + 42f,
            referenceTextPaint
        )

        // =====================================================
        // ROTATING INDICATOR
        // =====================================================

        val a =
            Math.toRadians(
                visualRotation.toDouble()
            )

        val indicatorRadius =
            radius - 30f

        val ix =
            cx +
                    indicatorRadius *
                    cos(a).toFloat()

        val iy =
            cy +
                    indicatorRadius *
                    sin(a).toFloat()

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
