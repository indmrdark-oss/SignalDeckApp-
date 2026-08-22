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

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private var lastAngle = 0.0
    private var touching = false

    private val minFrequency = 1.0
    private val maxFrequency = 20000.0

    override fun onDraw(
        canvas: Canvas
    ) {

        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        val radius =
            min(width, height) * 0.38f

        paint.style = Paint.Style.FILL
        paint.color = 0xFF181818.toInt()

        canvas.drawCircle(
            cx,
            cy,
            radius,
            paint
        )

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = 0xFF555555.toInt()

        canvas.drawCircle(
            cx,
            cy,
            radius,
            paint
        )

        val startAngle = -135.0

        val referenceRadius =
            radius * 0.90f

        val refRad =
            Math.toRadians(startAngle)

        val x1 =
            cx +
                cos(refRad).toFloat() *
                (radius * 0.72f)

        val y1 =
            cy +
                sin(refRad).toFloat() *
                (radius * 0.72f)

        val x2 =
            cx +
                cos(refRad).toFloat() *
                referenceRadius

        val y2 =
            cy +
                sin(refRad).toFloat() *
                referenceRadius

        paint.strokeWidth = 5f
        paint.color = 0xFFFFFFFF.toInt()

        canvas.drawLine(
            x1,
            y1,
            x2,
            y2,
            paint
        )

        paint.style = Paint.Style.FILL
        paint.textSize = radius * 0.14f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER

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

        val normalized =
            (
                currentFrequency -
                    minFrequency
            ) /
                (
                    maxFrequency -
                        minFrequency
                )

        val angle =
            -135.0 +
                normalized * 270.0

        val rad =
            Math.toRadians(angle)

        val needleLength =
            radius * 0.68f

        val nx =
            cx +
                cos(rad).toFloat() *
                needleLength

        val ny =
            cy +
                sin(rad).toFloat() *
                needleLength

        paint.strokeWidth = 8f
        paint.color = 0xFFFF4444.toInt()

        canvas.drawLine(
            cx,
            cy,
            nx,
            ny,
            paint
        )

        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()

        canvas.drawCircle(
            cx,
            cy,
            10f,
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

                lastAngle =
                    angleFromPoint(
                        event.x,
                        event.y,
                        cx,
                        cy
                    )

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                if (!touching) {
                    return true
                }

                val angle =
                    angleFromPoint(
                        event.x,
                        event.y,
                        cx,
                        cy
                    )

                var delta =
                    angle - lastAngle

                if (delta > 180.0) {
                    delta -= 360.0
                }

                if (delta < -180.0) {
                    delta += 360.0
                }

                lastAngle = angle

                onRotate?.invoke(delta)

                invalidate()

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

                touching = false

                return true
            }
        }

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
