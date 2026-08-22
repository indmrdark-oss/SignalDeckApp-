package com.signaldeck.scope

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class DialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onRotate: ((Double) -> Unit)? = null

    var currentFrequency: Double = 1000.0
        set(value) {
            field = value.coerceIn(minFrequency, maxFrequency)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var lastAngle = 0.0
    private var touching = false

    private val minFrequency = 1.0
    private val maxFrequency = 20000.0

    // Prevent tiny finger noise from making the dial jitter.
    private var accumulatedDegrees = 0.0

    // Minimum angular movement before sending a frequency update.
    private val angleThreshold = 0.15

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        val radius = min(width, height) * 0.38f

        // =====================================================
        // DIAL BODY
        // =====================================================

        paint.style = Paint.Style.FILL
        paint.color = 0xFF181818.toInt()

        canvas.drawCircle(
            cx,
            cy,
            radius,
            paint
        )

        // =====================================================
        // OUTER RING
        // =====================================================

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = 0xFF555555.toInt()

        canvas.drawCircle(
            cx,
            cy,
            radius,
            paint
        )

        // =====================================================
        // SCALE
        // =====================================================

        val startAngle = -135.0
        val sweepAngle = 270.0

        paint.strokeWidth = 3f
        paint.color = 0xFF777777.toInt()

        for (i in 0..20) {

            val angle =
                startAngle +
                        sweepAngle * (i / 20.0)

            val rad =
                Math.toRadians(angle)

            val outer =
                radius * 0.91f

            val inner =
                if (i % 5 == 0) {
                    radius * 0.78f
                } else {
                    radius * 0.84f
                }

            val x1 =
                cx +
                        cos(rad).toFloat() *
                        inner

            val y1 =
                cy +
                        sin(rad).toFloat() *
                        inner

            val x2 =
                cx +
                        cos(rad).toFloat() *
                        outer

            val y2 =
                cy +
                        sin(rad).toFloat() *
                        outer

            canvas.drawLine(
                x1,
                y1,
                x2,
                y2,
                paint
            )
        }

        // =====================================================
        // 1 Hz REFERENCE
        // =====================================================

        val refRad =
            Math.toRadians(startAngle)

        paint.strokeWidth = 5f
        paint.color = 0xFFFFFFFF.toInt()

        val refX1 =
            cx +
                    cos(refRad).toFloat() *
                    (radius * 0.72f)

        val refY1 =
            cy +
                    sin(refRad).toFloat() *
                    (radius * 0.72f)

        val refX2 =
            cx +
                    cos(refRad).toFloat() *
                    (radius * 0.90f)

        val refY2 =
            cy +
                    sin(refRad).toFloat() *
                    (radius * 0.90f)

        canvas.drawLine(
            refX1,
            refY1,
            refX2,
            refY2,
            paint
        )

        // =====================================================
        // 1 Hz LABEL
        // =====================================================

        paint.style = Paint.Style.FILL
        paint.textSize = radius * 0.14f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFFFFFFFF.toInt()

        canvas.drawText(
            "1 Hz",
            cx +
                    cos(refRad).toFloat() *
                    (radius * 0.58f),
            cy +
                    sin(refRad).toFloat() *
                    (radius * 0.58f),
            paint
        )

        // =====================================================
        // FREQUENCY NEEDLE
        // =====================================================

        val normalized =
            (
                currentFrequency - minFrequency
            ) /
                    (
                        maxFrequency - minFrequency
                    )

        val needleAngle =
            startAngle +
                    normalized * sweepAngle

        val needleRad =
            Math.toRadians(needleAngle)

        val needleLength =
            radius * 0.68f

        val nx =
            cx +
                    cos(needleRad).toFloat() *
                    needleLength

        val ny =
            cy +
                    sin(needleRad).toFloat() *
                    needleLength

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFFFF4444.toInt()

        canvas.drawLine(
            cx,
            cy,
            nx,
            ny,
            paint
        )

        // =====================================================
        // CENTER
        // =====================================================

        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()

        canvas.drawCircle(
            cx,
            cy,
            11f,
            paint
        )

        paint.color = 0xFF444444.toInt()

        canvas.drawCircle(
            cx,
            cy,
            5f,
            paint
        )
    }

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        val parentView = parent

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                touching = true

                accumulatedDegrees = 0.0

                lastAngle =
                    angleFromPoint(
                        event.x,
                        event.y,
                        width / 2f,
                        height / 2f
                    )

                // VERY IMPORTANT:
                // Stop the ScrollView from stealing
                // the finger while rotating the dial.
                parentView?.requestDisallowInterceptTouchEvent(true)

                performClick()

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                if (!touching) {
                    return true
                }

                val cx = width / 2f
                val cy = height / 2f

                val currentAngle =
                    angleFromPoint(
                        event.x,
                        event.y,
                        cx,
                        cy
                    )

                var delta =
                    currentAngle - lastAngle

                // Handle crossing -180/+180.
                if (delta > 180.0) {
                    delta -= 360.0
                }

                if (delta < -180.0) {
                    delta += 360.0
                }

                lastAngle = currentAngle

                accumulatedDegrees += delta

                // Only send meaningful movements.
                if (
                    kotlin.math.abs(
                        accumulatedDegrees
                    ) >= angleThreshold
                ) {

                    val sendDelta =
                        accumulatedDegrees

                    accumulatedDegrees = 0.0

                    onRotate?.invoke(
                        sendDelta
                    )
                }

                invalidate()

                return true
            }

            MotionEvent.ACTION_UP -> {

                touching = false

                accumulatedDegrees = 0.0

                parentView?.requestDisallowInterceptTouchEvent(
                    false
                )

                performClick()

                return true
            }

            MotionEvent.ACTION_CANCEL -> {

                touching = false

                accumulatedDegrees = 0.0

                parentView?.requestDisallowInterceptTouchEvent(
                    false
                )

                return true
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun angleFromPoint(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float
    ): Double {

        return Math.toDegrees(
            atan2(
                (y - cy).toDouble(),
                (x - cx).toDouble()
            )
        )
    }
}
