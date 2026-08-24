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
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin

/**
 * Soft frequency knob.
 *
 * The needle follows your finger 1:1: wherever you grab the knob, the needle
 * stays put, and then rotates exactly with your finger (real-knob feel).
 * One full 270° sweep covers the whole 1 Hz → 20 kHz range — no multiple turns.
 *
 * Two scales:
 *  - "linear": 270° = 1 Hz → 20 kHz, evenly
 *  - "log":    270° = 1 Hz → 20 kHz, logarithmic — much finer at low Hz
 *              (where inverter work happens)
 */
class DialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Fires on EVERY frequency change while dragging — use for UI/readouts. */
    var onFrequency: ((Double) -> Unit)? = null

    /** Fires once when the finger lifts, with the final frequency — use for the serial send. */
    var onCommit: ((Double) -> Unit)? = null

    var scaleMode: String = "linear"
        set(value) {
            field = value
            invalidate()
        }

    var currentFrequency: Double = 1000.0
        private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val minFrequency = 1.0
    private val maxFrequency = 20000.0
    private val startAngle = -135.0
    private val endAngle = 135.0

    private var touching = false
    private var grabOffset = 0.0

    /** Set the needle from outside (nudge buttons, arrows). */
    fun setFrequency(f: Double) {
        currentFrequency = f.coerceIn(minFrequency, maxFrequency)
        invalidate()
    }

    private fun angleForFrequency(f: Double): Double {
        val clamped = f.coerceIn(minFrequency, maxFrequency)
        val t = if (scaleMode == "log") {
            (log10(clamped) - log10(minFrequency)) / (log10(maxFrequency) - log10(minFrequency))
        } else {
            (clamped - minFrequency) / (maxFrequency - minFrequency)
        }
        return startAngle + t.coerceIn(0.0, 1.0) * (endAngle - startAngle)
    }

    private fun frequencyForAngle(a: Double): Double {
        val t = ((a - startAngle) / (endAngle - startAngle)).coerceIn(0.0, 1.0)
        return if (scaleMode == "log") {
            Math.pow(10.0, log10(minFrequency) + t * (log10(maxFrequency) - log10(minFrequency)))
        } else {
            minFrequency + t * (maxFrequency - minFrequency)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.38f

        // face
        paint.style = Paint.Style.FILL
        paint.color = 0xFF181818.toInt()
        canvas.drawCircle(cx, cy, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = 0xFF555555.toInt()
        canvas.drawCircle(cx, cy, radius, paint)

        // start tick + "1 Hz"
        val refRad = Math.toRadians(startAngle)
        val referenceRadius = radius * 0.90f
        paint.strokeWidth = 5f
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawLine(
            cx + cos(refRad).toFloat() * (radius * 0.72f),
            cy + sin(refRad).toFloat() * (radius * 0.72f),
            cx + cos(refRad).toFloat() * referenceRadius,
            cy + sin(refRad).toFloat() * referenceRadius,
            paint
        )
        // end tick
        val endRad = Math.toRadians(endAngle)
        canvas.drawLine(
            cx + cos(endRad).toFloat() * (radius * 0.72f),
            cy + sin(endRad).toFloat() * (radius * 0.72f),
            cx + cos(endRad).toFloat() * referenceRadius,
            cy + sin(endRad).toFloat() * referenceRadius,
            paint
        )

        paint.style = Paint.Style.FILL
        paint.textSize = radius * 0.12f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFFAAAAAA.toInt()
        canvas.drawText("1 Hz", cx + cos(refRad).toFloat() * (radius * 0.55f), cy + sin(refRad).toFloat() * (radius * 0.55f), paint)
        canvas.drawText("20k", cx + cos(endRad).toFloat() * (radius * 0.55f), cy + sin(endRad).toFloat() * (radius * 0.55f), paint)

        // scale label
        paint.textSize = radius * 0.10f
        paint.color = 0xFF6F9A80.toInt()
        canvas.drawText(if (scaleMode == "log") "LOG" else "LIN", cx, cy + radius * 0.45f, paint)

        // needle
        val angle = angleForFrequency(currentFrequency)
        val rad = Math.toRadians(angle)
        val needleLength = radius * 0.68f
        paint.strokeWidth = 8f
        paint.color = 0xFFFF4444.toInt()
        canvas.drawLine(
            cx, cy,
            cx + cos(rad).toFloat() * needleLength,
            cy + sin(rad).toFloat() * needleLength,
            paint
        )
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, 10f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Claim the gesture so the parent ScrollView / slide page
                // can't steal it mid-drag.
                parent?.requestDisallowInterceptTouchEvent(true)

                touching = true
                // Remember where the finger grabbed relative to the needle,
                // so grabbing the knob never makes the needle jump.
                grabOffset = angleFromPoint(event.x, event.y, cx, cy) - angleForFrequency(currentFrequency)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!touching) return true
                parent?.requestDisallowInterceptTouchEvent(true)

                val needleNow = angleForFrequency(currentFrequency)
                val finger = angleFromPoint(event.x, event.y, cx, cy)
                var target = finger - grabOffset

                // Unwrap to the nearest equivalent angle so the needle
                // never spins around 360° chasing the finger.
                while (target - needleNow > 180.0) target -= 360.0
                while (needleNow - target > 180.0) target += 360.0

                // Soft stops: the needle clamps at 1 Hz / 20 kHz instead of wrapping.
                target = target.coerceIn(startAngle, endAngle)

                val newFreq = frequencyForAngle(target)
                if (Math.abs(newFreq - currentFrequency) > 0.0005) {
                    currentFrequency = newFreq
                    invalidate()
                    onFrequency?.invoke(currentFrequency)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    onCommit?.invoke(currentFrequency)
                }
                return true
            }
        }

        return true
    }

    private fun angleFromPoint(x: Float, y: Float, cx: Float, cy: Float): Double {
        return Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
    }
}
