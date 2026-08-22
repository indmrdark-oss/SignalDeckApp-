package com.signaldeck.scope

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private var serial: UsbSerialManager? = null

    private var readThread: Thread? = null
    @Volatile
    private var keepReading = false

    private lateinit var scopeView: ScopeView
    private lateinit var dialView: DialView

    private lateinit var connStatus: TextView
    private lateinit var connectBtn: Button
    private lateinit var liveCaptureBtn: Button
    private lateinit var reconBtn: Button
    private lateinit var cmdInput: EditText
    private lateinit var sendBtn: Button
    private lateinit var logView: TextView
    private lateinit var clearLogBtn: Button

    private lateinit var rTarget: TextView
    private lateinit var rTargetBig: TextView
    private lateinit var rMeasured: TextView
    private lateinit var rDuty: TextView
    private lateinit var rRate: TextView
    private lateinit var rVoltage: TextView

    private lateinit var coarseModeBtn: Button
    private lateinit var fineModeBtn: Button

    private lateinit var minus1Btn: Button
    private lateinit var minus01Btn: Button
    private lateinit var plus01Btn: Button
    private lateinit var plus1Btn: Button

    private lateinit var downFreqBtn: Button
    private lateinit var upFreqBtn: Button

    private val ACTION_USB_PERMISSION =
        "com.signaldeck.scope.USB_PERMISSION"

    private var capturing = false
    private var capRate = 0.0
    private var capSamples: IntArray? = null

    private val lineBuffer = StringBuilder()

    private var lastMeasuredHz = 0.0
    private var lastDuty = 50.0
    private var lastWaveform = false

    private val uiHandler =
        Handler(Looper.getMainLooper())

    private var liveCaptureActive = false
    private var lastFrameArrivedMs = 0L

    // =========================================================
    // FREQUENCY CONTROL
    // =========================================================

    private var dialFrequency = 1000.0

    private val F_MIN = 1.0
    private val F_MAX = 20000.0

    private var hzPerDegree =
        3000.0 / 360.0

    private var pendingSend = false

    private val sendThrottleMs = 60L

    // =========================================================
    // HOLD ARROW CONTROL
    // =========================================================

    private var freqRepeatDirection = 0

    /*
     * Hold ↑ or ↓:
     *
     * First change happens immediately.
     * Then waits 300 ms.
     * Then changes every 100 ms.
     *
     * That's about 10 Hz/second.
     */
    private val freqRepeatRunnable =
        object : Runnable {

            override fun run() {

                if (freqRepeatDirection == 0) {
                    return
                }

                dialFrequency =
                    (
                        dialFrequency +
                            freqRepeatDirection * 1.0
                    ).coerceIn(
                        F_MIN,
                        F_MAX
                    )

                updateDialDisplay()

                serial?.writeLine(
                    "F%.2f".format(dialFrequency)
                )

                uiHandler.postDelayed(
                    this,
                    100L
                )
            }
        }

    // =========================================================
    // LIVE CAPTURE
    // =========================================================

    private val liveCaptureLoop =
        object : Runnable {

            override fun run() {

                if (!liveCaptureActive) {
                    return
                }

                if (!capturing) {
                    serial?.writeLine("C")
                }

                uiHandler.postDelayed(
                    this,
                    400L
                )
            }
        }

    // =========================================================
    // DIAL THROTTLE
    // =========================================================

    private val throttledSender =
        object : Runnable {

            override fun run() {

                if (pendingSend) {

                    serial?.writeLine(
                        "F%.2f".format(
                            dialFrequency
                        )
                    )

                    pendingSend = false
                }

                uiHandler.postDelayed(
                    this,
                    sendThrottleMs
                )
            }
        }

    // =========================================================
    // USB RECEIVER
    // =========================================================

    private val usbReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context,
                intent: Intent
            ) {

                try {

                    when (intent.action) {

                        ACTION_USB_PERMISSION -> {

                            val device: UsbDevice? =
                                if (Build.VERSION.SDK_INT >= 33) {

                                    intent.getParcelableExtra(
                                        UsbManager.EXTRA_DEVICE,
                                        UsbDevice::class.java
                                    )

                                } else {

                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra(
                                        UsbManager.EXTRA_DEVICE
                                    )
                                }

                            val granted =
                                intent.getBooleanExtra(
                                    UsbManager.EXTRA_USB_PERMISSION,
                                    false
                                )

                            if (
                                granted &&
                                device != null
                            ) {

                                connectToDevice(device)

                            } else {

                                appendLog(
                                    "Permission denied for USB device."
                                )
                            }
                        }

                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {

                            appendLog(
                                "Arduino attached. Tap Connect."
                            )
                        }

                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {

                            appendLog(
                                "Device detached."
                            )

                            disconnect()
                        }
                    }

                } catch (e: Exception) {

                    appendLog(
                        "CRASH in usbReceiver: " +
                            e.toString()
                    )
                }
            }
        }

    // =========================================================
    // ON CREATE
    // =========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        usbManager =
            getSystemService(
                Context.USB_SERVICE
            ) as UsbManager

        // -----------------------------------------------------
        // FIND VIEWS
        // -----------------------------------------------------

        scopeView =
            findViewById(R.id.scopeView)

        dialView =
            findViewById(R.id.dialView)

        connStatus =
            findViewById(R.id.connStatus)

        connectBtn =
            findViewById(R.id.connectBtn)

        liveCaptureBtn =
            findViewById(R.id.liveCaptureBtn)

        reconBtn =
            findViewById(R.id.reconBtn)

        cmdInput =
            findViewById(R.id.cmdInput)

        sendBtn =
            findViewById(R.id.sendBtn)

        logView =
            findViewById(R.id.logView)

        clearLogBtn =
            findViewById(R.id.clearLogBtn)

        rTarget =
            findViewById(R.id.rTarget)

        rTargetBig =
            findViewById(R.id.rTargetBig)

        rMeasured =
            findViewById(R.id.rMeasured)

        rDuty =
            findViewById(R.id.rDuty)

        rRate =
            findViewById(R.id.rRate)

        rVoltage =
            findViewById(R.id.rVoltage)

        coarseModeBtn =
            findViewById(R.id.coarseModeBtn)

        fineModeBtn =
            findViewById(R.id.fineModeBtn)

        minus1Btn =
            findViewById(R.id.minus1Btn)

        minus01Btn =
            findViewById(R.id.minus01Btn)

        plus01Btn =
            findViewById(R.id.plus01Btn)

        plus1Btn =
            findViewById(R.id.plus1Btn)

        downFreqBtn =
            findViewById(R.id.downFreqBtn)

        upFreqBtn =
            findViewById(R.id.upFreqBtn)

        // -----------------------------------------------------
        // USB BROADCAST
        // -----------------------------------------------------

        val filter =
            IntentFilter().apply {

                addAction(
                    ACTION_USB_PERMISSION
                )

                addAction(
                    UsbManager.ACTION_USB_DEVICE_ATTACHED
                )

                addAction(
                    UsbManager.ACTION_USB_DEVICE_DETACHED
                )
            }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            registerReceiver(
                usbReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            registerReceiver(
                usbReceiver,
                filter
            )
        }

        // -----------------------------------------------------
        // CONNECT
        // -----------------------------------------------------

        connectBtn.setOnClickListener {

            try {

                if (serial != null) {
                    disconnect()
                } else {
                    requestDevice()
                }

            } catch (e: Exception) {

                appendLog(
                    "CRASH on Connect tap: " +
                        e.toString()
                )
            }
        }

        // -----------------------------------------------------
        // LIVE CAPTURE
        // -----------------------------------------------------

        liveCaptureBtn.setOnClickListener {

            if (liveCaptureActive) {

                liveCaptureActive = false

                liveCaptureBtn.text =
                    "Start Live Capture"

                appendLog(
                    "Live capture stopped."
                )

            } else {

                liveCaptureActive = true

                liveCaptureBtn.text =
                    "Stop Live Capture"

                appendLog(
                    "Live capture started."
                )

                uiHandler.post(
                    liveCaptureLoop
                )
            }
        }

        // -----------------------------------------------------
        // RECONSTRUCTED
        // -----------------------------------------------------

        reconBtn.setOnClickListener {

            liveCaptureActive = false

            liveCaptureBtn.text =
                "Start Live Capture"

            rVoltage.text =
                "Voltage: -- (no real capture yet)"

            scopeView.showReconstructed()
        }

        // -----------------------------------------------------
        // CLEAR LOG
        // -----------------------------------------------------

        clearLogBtn.setOnClickListener {
            logView.text = ""
        }

        // -----------------------------------------------------
        // MANUAL COMMAND
        // -----------------------------------------------------

        sendBtn.setOnClickListener {

            val text =
                cmdInput.text
                    .toString()
                    .trim()

            if (text.isNotEmpty()) {

                appendCommandLog(text)

                serial?.writeLine(text)

                cmdInput.setText("")
            }
        }

        // =====================================================
        // ROTARY DIAL
        // =====================================================

        dialView.onRotate =
            { deltaDegrees ->

                val deltaHz =
                    deltaDegrees * hzPerDegree

                dialFrequency =
                    (
                        dialFrequency +
                            deltaHz
                    ).coerceIn(
                        F_MIN,
                        F_MAX
                    )

                updateDialDisplay()

                pendingSend = true
            }

        // -----------------------------------------------------
        // COARSE
        // -----------------------------------------------------

        coarseModeBtn.setOnClickListener {

            hzPerDegree =
                3000.0 / 360.0

            appendLog(
                "Dial: COARSE mode " +
                    "(1 turn ≈ 3000 Hz)"
            )
        }

        // -----------------------------------------------------
        // FINE
        // -----------------------------------------------------

        fineModeBtn.setOnClickListener {

            hzPerDegree =
                60.0 / 360.0

            appendLog(
                "Dial: FINE mode " +
                    "(1 turn ≈ 60 Hz)"
            )
        }

        // =====================================================
        // HOLD DOWN ARROW
        // =====================================================

        downFreqBtn.setOnTouchListener {
                _, event ->

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {

                    freqRepeatDirection = -1

                    changeFrequencyBy(
                        -1.0
                    )

                    startFrequencyRepeat()

                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {

                    stopFrequencyRepeat()

                    true
                }

                else -> true
            }
        }

        // =====================================================
        // HOLD UP ARROW
        // =====================================================

        upFreqBtn.setOnTouchListener {
                _, event ->

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {

                    freqRepeatDirection = 1

                    changeFrequencyBy(
                        1.0
                    )

                    startFrequencyRepeat()

                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {

                    stopFrequencyRepeat()

                    true
                }

                else -> true
            }
        }

        // =====================================================
        // OLD EXACT NUDGE BUTTONS
        // =====================================================

        minus1Btn.setOnClickListener {
            nudgeFrequency(-1.0)
        }

        minus01Btn.setOnClickListener {
            nudgeFrequency(-0.1)
        }

        plus01Btn.setOnClickListener {
            nudgeFrequency(0.1)
        }

        plus1Btn.setOnClickListener {
            nudgeFrequency(1.0)
        }

        // -----------------------------------------------------
        // INITIAL DISPLAY
        // -----------------------------------------------------

        updateDialDisplay()

        uiHandler.post(
            throttledSender
        )
    }

    // =========================================================
    // FREQUENCY CHANGE
    // =========================================================

    private fun changeFrequencyBy(
        deltaHz: Double
    ) {

        dialFrequency =
            (
                dialFrequency + deltaHz
            ).coerceIn(
                F_MIN,
                F_MAX
            )

        updateDialDisplay()

        serial?.writeLine(
            "F%.2f".format(
                dialFrequency
            )
        )
    }

    // =========================================================
    // START REPEAT
    // =========================================================

    private fun startFrequencyRepeat() {

        uiHandler.removeCallbacks(
            freqRepeatRunnable
        )

        /*
         * Wait 300 ms after the first step.
         * Then repeat every 100 ms.
         */
        uiHandler.postDelayed(
            freqRepeatRunnable,
            300L
        )
    }

    // =========================================================
    // STOP REPEAT
    // =========================================================

    private fun stopFrequencyRepeat() {

        freqRepeatDirection = 0

        uiHandler.removeCallbacks(
            freqRepeatRunnable
        )
    }

    // =========================================================
    // NUDGE BUTTON
    // =========================================================

    private fun nudgeFrequency(
        deltaHz: Double
    ) {

        changeFrequencyBy(
            deltaHz
        )
    }

    // =========================================================
    // DISPLAY
    // =========================================================

    private fun updateDialDisplay() {

        rTargetBig.text =
            "%.2f Hz".format(
                dialFrequency
            )
    }

    // =========================================================
    // USB DEVICE REQUEST
    // =========================================================

    private fun requestDevice() {

        val devices =
            usbManager.deviceList.values

        if (devices.isEmpty()) {

            appendLog(
                "No USB device found. " +
                    "Check the cable and that " +
                    "the Arduino is plugged in."
            )

            return
        }

        val device =
            devices.first()

        val usbPermissionIntent =
            Intent(
                ACTION_USB_PERMISSION
            ).apply {

                setPackage(
                    packageName
                )
            }

        val flags =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
            ) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }

        val permissionIntent =
            PendingIntent.getBroadcast(
                this,
                0,
                usbPermissionIntent,
