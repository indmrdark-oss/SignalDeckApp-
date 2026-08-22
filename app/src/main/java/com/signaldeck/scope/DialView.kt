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

    /*
     * MainActivity receives the ACTUAL frequency here.
     *
     * No degrees.
     * No hzPerDegree.
     * No 3000/360.
     */
    var onFrequencySelected:
        ((Double) -> Unit)? = null

    var currentFrequency: Double = 1000.0
        set(value) {

            field =
                value.coerceIn(
                    minFrequency,
                    maxFrequency
                )

            invalidate()
        }

    private val paint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        )

    private val minFrequency =
        1.0

    private val maxFrequency =
        20000.0

    /*
     * Physical dial range.
     *
     * -135° = 1 Hz
     * +135° = 20,000 Hz
     */
    private val startAngle =
        -135.0

    private val sweepAngle =
        270.0

    private var touching =
        false

    override fun onDraw(
        canvas: Canvas
    ) {

        super.onDraw(canvas)

        val cx =
            width / 2f

        val cy =
            height / 2f

        val radius =
            min(
                width,
                height
            ) * 0.38f

        // =====================================================
        // BODY
        // =====================================================

        paint.style =
            Paint.Style.FILL

        paint.color =
            0xFF181818.toInt()

        canvas.drawCircle(
            cx,
            cy,
            radius,
            paint
        )

        // =====================================================
        // OUTER RING
        // =====================================================

        paint.style =
            Paint.Style.STROKE

        paint.strokeWidth =
            6f

        paint.color =
            0xFF555555.toInt()

        canvas.drawCircle(
            cx,
            cy,
            radius,
            paint
        )

        // =====================================================
        // TICKS
        // =====================================================

        paint.strokeWidth =
            3f

        paint.color =
            0xFF777777.toInt()

        for (i in 0..20) {

            val angle =
                startAngle +
                        sweepAngle *
                        (
                            i / 20.0
                        )

            val rad =
                Math.toRadians(
                    angle
                )

            val outer =
                radius * 0.91f

            val inner =
                if (i % 5 == 0) {
                    radius * 0.77f
                } else {
                    radius * 0.84f
                }

            canvas.drawLine(

                cx +
                        cos(rad).toFloat() *
                        inner,

                cy +
                        sin(rad).toFloat() *
                        inner,

                cx +
                        cos(rad).toFloat() *
                        outer,

                cy +
                        sin(rad).toFloat() *
                        outer,

                paint
            )
        }

        // =====================================================
        // 1 Hz REFERENCE
        // =====================================================

        val refRad =
            Math.toRadians(
                startAngle
            )

        paint.strokeWidth =
            5f

        paint.color =
            0xFFFFFFFF.toInt()

        canvas.drawLine(

            cx +
                    cos(refRad).toFloat() *
                    radius * 0.72f,

            cy +
                    sin(refRad).toFloat() *
                    radius * 0.72f,

            cx +
                    cos(refRad).toFloat() *
                    radius * 0.90f,

            cy +
                    sin(refRad).toFloat() *
                    radius * 0.90f,

            paint
        )

        // =====================================================
        // 1 Hz LABEL
        // =====================================================

        paint.style =
            Paint.Style.FILL

        paint.textSize =
            radius * 0.14f

        paint.typeface =
            Typeface.DEFAULT_BOLD

        paint.textAlign =
            Paint.Align.CENTER

        paint.color =
            0xFFFFFFFF.toInt()

        canvas.drawText(

            "1 Hz",

            cx +
                    cos(refRad).toFloat() *
                    radius * 0.58f,

            cy +
                    sin(refRad).toFloat() *
                    radius * 0.58f,

            paint
        )

        // =====================================================
        // NEEDLE
        // =====================================================

        val normalized =
            (
                currentFrequency -
                        minFrequency
            ) /
                    (
                        maxFrequency -
                                minFrequency
                    )

        val needleAngle =
            startAngle +
                    normalized *
                    sweepAngle

        val needleRad =
            Math.toRadians(
                needleAngle
            )

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

        paint.style =
            Paint.Style.STROKE

        paint.strokeWidth =
            8f

        paint.strokeCap =
            Paint.Cap.ROUND

        paint.color =
            0xFFFF4444.toInt()

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

        paint.style =
            Paint.Style.FILL

        paint.color =
            0xFFFFFFFF.toInt()

        canvas.drawCircle(
            cx,
            cy,
            11f,
            paint
        )

        paint.color =
            0xFF444444.toInt()

        canvas.drawCircle(
            cx,
            cy,
            5f,
            paint
        )
    }

    // =========================================================
    // TOUCH
    // =========================================================

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        val cx =
            width / 2f

        val cy =
            height / 2f

        when (
            event.actionMasked
        ) {

            MotionEvent.ACTION_DOWN -> {

                touching =
                    true

                /*
                 * VERY IMPORTANT:
                 *
                 * The dial is inside a ScrollView.
                 * Don't allow ScrollView to steal
                 * the finger gesture.
                 */
                parent?.requestDisallowInterceptTouchEvent(
                    true
                )

                /*
                 * Instant jump.
                 */
                selectFrequency(
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

                selectFrequency(
                    event.x,
                    event.y,
                    cx,
                    cy
                )

                return true
            }

            MotionEvent.ACTION_UP -> {

                touching =
                    false

                parent?.requestDisallowInterceptTouchEvent(
                    false
                )

                performClick()

                return true
            }

            MotionEvent.ACTION_CANCEL -> {

                touching =
                    false

                parent?.requestDisallowInterceptTouchEvent(
                    false
                )

                return true
            }
        }

        return true
    }

    // =========================================================
    // TOUCH → FREQUENCY
    // =========================================================

    private fun selectFrequency(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float
    ) {

        val angle =
            Math.toDegrees(
                atan2(
                    (
                        y - cy
                    ).toDouble(),

                    (
                        x - cx
                    ).toDouble()
                )
            )

        var relative =
            angle - startAngle

        /*
         * Normalize angle.
         */
        while (
            relative < 0.0
        ) {
            relative += 360.0
        }

        while (
            relative >= 360.0
        ) {
            relative -= 360.0
        }

        /*
         * The dial only occupies 270°.
         *
         * The remaining 90° is the dead area.
         */
        if (
            relative > sweepAngle
        ) {
            return
        }

        /*
         * 0.0 → 1.0
         */
        val normalized =
            (
                relative /
                        sweepAngle
            ).coerceIn(
                0.0,
                1.0
            )

        /*
         * DIRECT MAPPING:
         *
         * 0.0 = 1 Hz
         * 1.0 = 20000 Hz
         *
         * No intermediate scaling.
         */
        val frequency =
            minFrequency +
                    normalized *
                    (
                        maxFrequency -
                                minFrequency
                    )

        currentFrequency =
            frequency

        /*
         * Send the exact frequency.
         */
        onFrequencySelected?.invoke(
            frequency
        )
    }

    override fun performClick(): Boolean {

        super.performClick()

        return true
    }
}
