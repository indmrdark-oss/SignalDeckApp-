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
 * SignalDeck rotary frequency dial.
 *
 * - Relative rotary control
 * - Prevents parent ScrollView from stealing the gesture
 * - Fixed 1 Hz reference marker
 * - Green indicator shows current dial rotation
 * - Reports only angular DELTA to MainActivity
 */
class DialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // =========================================================
    // PAINTS
    // =========================================================

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

    // Fixed 1 Hz reference marker
    private val referencePaint = Paint().apply {
        color = Color.parseColor("#FFFFFF")
        strokeWidth = 7f
        isAntiAlias = true
    }

    // Current rotating indicator
    private val indicatorPaint = Paint().apply {
        color = Color.parseColor("#4DFFA0")
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val centerPaint = Paint().apply {
        color = Color.parseColor("#4DFFA0")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // =========================================================
    // ROTATION CALLBACK
    // =========================================================

    var onRotate: ((deltaDegrees: Float) -> Unit)? = null

    // =========================================================
    // TOUCH STATE
    // =========================================================

    private var lastAngle = 0f

    /**
     * Purely visual rotation.
     *
     * This is NOT the frequency.
     * MainActivity controls the actual frequency.
     */
    private var visualRotation = -90f

    private var isRotating = false

    // =========================================================
    // TOUCH HANDLING
    // =========================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {

        val cx = width / 2f
        val cy = height / 2f

        val dx = event.x - cx
        val dy = event.y - cy

        val angle = Math.toDegrees(
            atan2(
                dy.toDouble(),
                dx.toDouble()
            )
        ).toFloat()

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                /*
                 * IMPORTANT:
                 *
                 * Tell the parent ScrollView:
                 * "Do NOT steal this gesture."
                 *
                 * This prevents the screen from scrolling when
                 * the user is trying to rotate the dial.
                 */
                parent?.requestDisallowInterceptTouchEvent(true)

                lastAngle = angle
                isRotating = true

                /*
                 * Return true so this view owns the gesture.
                 */
                return true
            }

            MotionEvent.ACTION_MOVE -> {

                if (!isRotating) {
                    return true
                }

                /*
                 * Continue preventing ScrollView interception.
                 */
                parent?.requestDisallowInterceptTouchEvent(true)

                var delta = angle - lastAngle

                // Handle crossing +180 / -180 boundary.
                if (delta > 180f) {
                    delta -= 360f
                }

                if (delta < -180f) {
                    delta += 360f
                }

                lastAngle = angle

                /*
                 * Ignore extremely tiny touchscreen jitter.
                 *
                 * This prevents accidental frequency movement when
                 * the finger barely moves.
                 */
                if (kotlin.math.abs(delta) > 0.05f) {

                    visualRotation += delta

                    /*
                     * Keep visualRotation reasonably small so it
                     * doesn't grow to an enormous number after
                     * thousands of rotations.
                     */
                    if (visualRotation > 360000f ||
                        visualRotation < -360000f
                    ) {
                        visualRotation %= 360f
                    }

                    invalidate()

                    onRotate?.invoke(delta)
                }

                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

                isRotating = false

                /*
                 * Allow the parent ScrollView to scroll again
                 * after the dial gesture has ended.
                 */
                parent?.requestDisallowInterceptTouchEvent(false)

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

    // =========================================================
    // DRAW
    // =========================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        val radius =
            (minOf(width, height) / 2f) - 12f

        // -----------------------------------------------------
        // OUTER DIAL
        // -----------------------------------------------------

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

        // -----------------------------------------------------
        // TICK MARKS
        // -----------------------------------------------------

        for (i in 0 until 36) {

            val a =
                Math.toRadians(
                    (i * 10).toDouble()
                )

            val r1 =
                radius - 14f

            val r2 =
                radius - 4f

            val x1 =
                cx +
                        (r1 * cos(a)).toFloat()

            val y1 =
                cy +
                        (r1 * sin(a)).toFloat()

            val x2 =
                cx +
                        (r2 * cos(a)).toFloat()

            val y2 =
                cy +
                        (r2 * sin(a)).toFloat()

            canvas.drawLine(
                x1,
                y1,
                x2,
                y2,
                tickPaint
            )
        }

        // -----------------------------------------------------
        // FIXED 1 Hz REFERENCE MARKER
        // -----------------------------------------------------
        //
        // This NEVER rotates.
        //
        // It is positioned at the top of the dial.
        // So this is your permanent "1 Hz starting point".
        //

        val referenceAngle =
            Math.toRadians(-90.0)

        val refOuter =
            radius - 2f

        val refInner =
            radius - 25f

        val refX1 =
            cx +
                    (refInner * cos(referenceAngle)).toFloat()

        val refY1 =
            cy +
                    (refInner * sin(referenceAngle)).toFloat()

        val refX2 =
            cx +
                    (refOuter * cos(referenceAngle)).toFloat()

        val refY2 =
            cy +
                    (refOuter * sin(referenceAngle)).toFloat()

        canvas.drawLine(
            refX1,
            refY1,
            refX2,
            refY2,
            referencePaint
        )

        // -----------------------------------------------------
        // CURRENT ROTATING INDICATOR
        // -----------------------------------------------------

        val indicatorAngle =
            Math.toRadians(
                visualRotation.toDouble()
            )

        val indicatorRadius =
            radius - 30f

        val ix =
            cx +
                    indicatorRadius *
                    cos(indicatorAngle).toFloat()

        val iy =
            cy +
                    indicatorRadius *
                    sin(indicatorAngle).toFloat()

        canvas.drawLine(
            cx,
            cy,
            ix,
            iy,
            indicatorPaint
        )

        // -----------------------------------------------------
        // CENTER
        // -----------------------------------------------------

        canvas.drawCircle(
            cx,
            cy,
            14f,
            centerPaint
        )
    }
}
