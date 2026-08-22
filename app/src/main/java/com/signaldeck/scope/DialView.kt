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
            field = value.coerceIn(1.0, 20000.0)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val minFrequency = 1.0
    private val maxFrequency = 20000.0

    private val startAngle = -135.0
    private val sweepAngle = 270.0

    private var touching = false

    override fun onDraw(canvas: Canvas) {

        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.38f

        // Dial body
        paint.style = Paint.Style.FILL
        paint.color = 0xFF181818.toInt()

        canvas.drawCircle(cx, cy, radius, paint)

        // Outer ring
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = 0xFF555555.toInt()

        canvas.drawCircle(cx, cy, radius, paint)

        // Tick marks
        paint.strokeWidth = 3f
        paint.color = 0xFF777777.toInt()

        for (i in 0..20) {

            val angle =
                startAngle +
                        sweepAngle * (i / 20.0)

            val rad = Math.toRadians(angle)

            val outer = radius * 0.91f

            val inner =
                if (i % 5 == 0)
                    radius * 0.77f
                else
                    radius * 0.84f

            canvas.drawLine(
                cx + cos(rad).toFloat() * inner,
                cy + sin(rad).toFloat() * inner,
                cx + cos(rad).toFloat() * outer,
                cy + sin(rad).toFloat() * outer,
                paint
            )
        }

        // 1 Hz reference mark
        val refRad = Math.toRadians(startAngle)

        paint.strokeWidth = 5f
        paint.color = 0xFFFFFFFF.toInt()

        canvas.drawLine(
            cx + cos(refRad).toFloat() * radius * 0.72f,
            cy + sin(refRad).toFloat() * radius * 0.72f,
            cx + cos(refRad).toFloat() * radius * 0.90f,
            cy + sin(refRad).toFloat() * radius * 0.90f,
            paint
        )

        // 1 Hz label
        paint.style = Paint.Style.FILL
        paint.textSize = radius * 0.14f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFFFFFFFF.toInt()

        canvas.drawText(
            "1 Hz",
            cx + cos(refRad).toFloat() * radius * 0.58f,
            cy + sin(refRad).toFloat() * radius * 0.58f,
            paint
        )

        // Needle
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

        val needleLength = radius * 0.68f

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

        // Center
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

        val cx = width / 2f
        val cy = height / 2f

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                touching = true

                // Stop ScrollView from stealing the gesture.
                parent?.requestDisallowInterceptTouchEvent(true)

                // Jump immediately to where the finger touched.
                moveToTouch(
                    event.x,
                    event.y,
                    cx,
                    cy
                )

                performClick()

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                if (!touching) {
                    return true
                }

                moveToTouch(
                    event.x,
                    event.y,
                    cx,
                    cy
                )

                return true
            }

            MotionEvent.ACTION_UP -> {

                touching = false

                parent?.requestDisallowInterceptTouchEvent(false)

                performClick()

                return true
            }

            MotionEvent.ACTION_CANCEL -> {

                touching = false

                parent?.requestDisallowInterceptTouchEvent(false)

                return true
            }
        }

        return true
    }

    private fun moveToTouch(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float
    ) {

        val angle =
            Math.toDegrees(
                atan2(
                    (y - cy).toDouble(),
                    (x - cx).toDouble()
                )
            )

        var relative =
            angle - startAngle

        while (relative < 0.0) {
            relative += 360.0
        }

        while (relative >= 360.0) {
            relative -= 360.0
        }

        // Ignore the unused 90° section.
        if (relative > sweepAngle) {
            return
        }

        val newFrequency =
            minFrequency +
                    (
                        relative / sweepAngle
                    ) *
                    (
                        maxFrequency -
                                minFrequency
                    )

        /*
         * IMPORTANT:
         *
         * MainActivity expects onRotate() to receive
         * DEGREES, not Hz.
         *
         * So calculate the frequency difference and
         * convert it back to dial degrees.
         */
        val frequencyDifference =
            newFrequency - currentFrequency

        val hzPerDegree =
            (
                maxFrequency - minFrequency
            ) / sweepAngle

        val degreeChange =
            frequencyDifference / hzPerDegree

        if (degreeChange != 0.0) {
            onRotate?.invoke(degreeChange)
        }

        // Update visual position immediately.
        currentFrequency = newFrequency
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
