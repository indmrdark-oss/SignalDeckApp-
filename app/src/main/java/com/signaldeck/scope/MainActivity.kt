package com.signaldeck.scope

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

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
    private lateinit var upFreqBtn

    private lateinit var speed1xBtn: Button
    private lateinit var speed2xBtn: Button

    private val ACTION_USB_PERMISSION =
        "com.signaldeck.scope.USB_PERMISSION"

    private val handler =
        Handler(Looper.getMainLooper())

    private var dialFrequency = 1000.0

    private val F_MIN = 1.0
    private val F_MAX = 20000.0

    private var hzPerDegree = 3000.0 / 360.0

    private var arrowSpeed = 1

    private var repeatDirection = 0

    private var liveCapture = false

    private val repeatRunnable = object : Runnable {

        override fun run() {

            if (repeatDirection == 0) {
                return
            }

            changeFrequencyFromArrow()

            val delay =
                if (arrowSpeed == 1) {
                    100L
                } else {
                    50L
                }

            handler.postDelayed(this, delay)
        }
    }

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
                                    UsbManager.EXTRA_PERMISSION_GRANTED,
                                    false
                                )

                            if (granted && device != null) {
                                connectToDevice(device)
                            } else {
                                appendLog("USB permission denied.")
                            }
                        }

                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                            appendLog("Arduino attached. Tap Connect.")
                        }

                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            appendLog("USB device detached.")
                            disconnect()
                        }
                    }

                } catch (e: Exception) {

                    appendLog(
                        "USB receiver error: ${e.message}"
                    )
                }
            }
        }

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

        scopeView = findViewById(R.id.scopeView)
        dialView = findViewById(R.id.dialView)

        connStatus = findViewById(R.id.connStatus)
        connectBtn = findViewById(R.id.connectBtn)

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

        speed1xBtn =
            findViewById(R.id.speed1xBtn)

        speed2xBtn =
            findViewById(R.id.speed2xBtn)

        setupUsbReceiver()
        setupButtons()
        setupDial()

        updateDialDisplay()
    }

    private fun setupUsbReceiver() {

        val filter =
            IntentFilter().apply {

                addAction(ACTION_USB_PERMISSION)

                addAction(
                    UsbManager.ACTION_USB_DEVICE_ATTACHED
                )

                addAction(
                    UsbManager.ACTION_USB_DEVICE_DETACHED
                )
            }

        if (Build.VERSION.SDK_INT >= 33) {

            registerReceiver(
                usbReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )

        } else {

            @Suppress("DEPRECATION")
            registerReceiver(
                usbReceiver,
                filter
            )
        }
    }

    private fun setupButtons() {

        connectBtn.setOnClickListener {

            if (serial != null) {
                disconnect()
            } else {
                requestDevice()
            }
        }

        clearLogBtn.setOnClickListener {
            logView.text = ""
        }

        sendBtn.setOnClickListener {

            val command =
                cmdInput.text
                    .toString()
                    .trim()

            if (command.isNotEmpty()) {

                appendCommandLog(command)

                serial?.writeLine(command)

                cmdInput.setText("")
            }
        }

        liveCaptureBtn.setOnClickListener {

            liveCapture =
                !liveCapture

            if (liveCapture) {

                liveCaptureBtn.text =
                    "Stop Live Capture"

                appendLog(
                    "Live capture started."
                )

            } else {

                liveCaptureBtn.text =
                    "Start Live Capture"

                appendLog(
                    "Live capture stopped."
                )
            }
        }

        reconBtn.setOnClickListener {

            liveCapture = false

            liveCaptureBtn.text =
                "Start Live Capture"

            try {
                scopeView.showReconstructed()
            } catch (_: Exception) {
            }

            rVoltage.text =
                "Voltage: --"
        }

        coarseModeBtn.setOnClickListener {

            hzPerDegree =
                3000.0 / 360.0

            appendLog(
                "Dial: COARSE — 1 rotation ≈ 3000 Hz"
            )
        }

        fineModeBtn.setOnClickListener {

            hzPerDegree =
                60.0 / 360.0

            appendLog(
                "Dial: FINE — 1 rotation ≈ 60 Hz"
            )
        }

        speed1xBtn.setOnClickListener {

            arrowSpeed = 1

            appendLog(
                "Arrow speed set to 1×"
            )
        }

        speed2xBtn.setOnClickListener {

            arrowSpeed = 2

            appendLog(
                "Arrow speed set to 2×"
            )
        }

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

        setupArrowButton(
            downFreqBtn,
            -1
        )

        setupArrowButton(
            upFreqBtn,
            1
        )
    }

    private fun setupArrowButton(
        button: Button,
        direction: Int
    ) {

        button.setOnTouchListener { _, event ->

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {

                    repeatDirection =
                        direction

                    changeFrequencyFromArrow()

                    handler.removeCallbacks(
                        repeatRunnable
                    )

                    handler.postDelayed(
                        repeatRunnable,
                        250L
                    )

                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {

                    repeatDirection = 0

                    handler.removeCallbacks(
                        repeatRunnable
                    )

                    true
                }

                else -> true
            }
        }
    }

    private fun setupDial() {

        dialView.onRotate =
            { degrees ->

                val change =
                    degrees * hzPerDegree

                dialFrequency =
                    (
                        dialFrequency + change
                    ).coerceIn(
                        F_MIN,
                        F_MAX
                    )

                updateDialDisplay()

                sendFrequency()
            }
    }

    private fun changeFrequencyFromArrow() {

        val step =
            if (arrowSpeed == 1) {
                1.0
            } else {
                5.0
            }

        dialFrequency =
            (
                dialFrequency +
                        repeatDirection * step
            ).coerceIn(
                F_MIN,
                F_MAX
            )

        updateDialDisplay()

        sendFrequency()
    }

    private fun nudgeFrequency(
        amount: Double
    ) {

        dialFrequency =
            (
                dialFrequency + amount
            ).coerceIn(
                F_MIN,
                F_MAX
            )

        updateDialDisplay()

        sendFrequency()
    }

    private fun updateDialDisplay() {

        val value =
            String.format(
                Locale.US,
                "%.2f Hz",
                dialFrequency
            )

        rTargetBig.text = value

        rTarget.text =
            "Target: $value"

        dialView.currentFrequency =
            dialFrequency

        dialView.invalidate()
    }

    private fun sendFrequency() {

        serial?.writeLine(
            String.format(
                Locale.US,
                "F%.2f",
                dialFrequency
            )
        )
    }

    private fun requestDevice() {

        val devices =
            usbManager.deviceList.values

        if (devices.isEmpty()) {

            appendLog(
                "No USB device found."
            )

            return
        }

        val device =
            devices.first()

        if (
            usbManager.hasPermission(device)
        ) {

            connectToDevice(device)

        } else {

            val intent =
                Intent(
                    ACTION_USB_PERMISSION
                ).setPackage(
                    packageName
                )

            val flags =
                if (Build.VERSION.SDK_INT >= 31) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }

            val permissionIntent =
                PendingIntent.getBroadcast(
                    this,
                    0,
                    intent,
                    flags
                )

            usbManager.requestPermission(
                device,
                permissionIntent
            )

            appendLog(
                "Requesting USB permission..."
            )
        }
    }

    private fun connectToDevice(
        device: UsbDevice
    ) {

        try {

            /*
             * This uses the UsbSerialManager API
             * already used by your project.
             *
             * If your UsbSerialManager constructor
             * differs, keep that class unchanged and
             * use its existing connection method here.
             */

            serial =
                UsbSerialManager(
                    this,
                    usbManager,
                    device
                )

            serial?.open()

            connStatus.text =
                "Connected"

            connectBtn.text =
                "Disconnect"

            appendLog(
                "USB serial connected."
            )

            startReading()

        } catch (e: Exception) {

            serial = null

            connStatus.text =
                "Disconnected"

            appendLog(
                "Connection failed: ${e.message}"
            )
        }
    }

    private fun startReading() {

        if (keepReading) {
            return
        }

        keepReading = true

        readThread =
            Thread {

                while (keepReading) {

                    try {

                        val data =
                            serial?.readLine()

                        if (
                            data != null &&
                            data.isNotEmpty()
                        ) {

                            handler.post {

                                processSerialLine(
                                    data
                                )
                            }
                        }

                    } catch (e: Exception) {

                        if (keepReading) {

                            handler.post {

                                appendLog(
                                    "Read error: ${e.message}"
                                )
                            }
                        }

                        break
                    }
                }
            }

        readThread?.start()
    }

    private fun processSerialLine(
        line: String
    ) {

        val text =
            line.trim()

        if (text.isEmpty()) {
            return
        }

        appendSerialLog(text)

        when {

            text.startsWith("FREQ:") -> {

                val value =
                    text.substringAfter(
                        ":"
                    ).toDoubleOrNull()

                if (value != null) {

                    rMeasured.text =
                        String.format(
                            Locale.US,
                            "Measured: %.2f Hz",
                            value
                        )
                }
            }

            text.startsWith("DUTY:") -> {

                val value =
                    text.substringAfter(
                        ":"
                    )

                rDuty.text =
                    "Duty: $value"
            }

            text.startsWith("RATE:") -> {

                val value =
                    text.substringAfter(
                        ":"
                    )

                rRate.text =
                    "Rate: $value"
            }

            text.startsWith("VOLT:") -> {

                val value =
                    text.substringAfter(
                        ":"
                    )

                rVoltage.text =
                    "Voltage: $value"
            }
        }
    }

    private fun disconnect() {

        keepReading = false

        handler.removeCallbacks(
            repeatRunnable
        )

        repeatDirection = 0

        try {
            readThread?.interrupt()
        } catch (_: Exception) {
        }

        readThread = null

        try {
            serial?.close()
        } catch (_: Exception) {
        }

        serial = null

        connStatus.text =
            "Disconnected"

        connectBtn.text =
            "Connect"

        appendLog(
            "Disconnected."
        )
    }

    private fun appendLog(
        message: String
    ) {

        handler.post {

            val current =
                logView.text.toString()

            logView.text =
                if (current.isEmpty()) {
                    message
                } else {
                    "$current\n$message"
                }
        }
    }

    private fun appendCommandLog(
        command: String
    ) {

        appendStyledLog(
            ">> $command",
            Color.CYAN
        )
    }

    private fun appendSerialLog(
        message: String
    ) {

        appendStyledLog(
            "<< $message",
            Color.GREEN
        )
    }

    private fun appendStyledLog(
        message: String,
        color: Int
    ) {

        val old =
            logView.text

        val text =
            SpannableString(
                "$message\n"
            )

        text.setSpan(
            ForegroundColorSpan(color),
            0,
            message.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        logView.append(text)
    }

    override fun onDestroy() {

        repeatDirection = 0

        handler.removeCallbacks(
            repeatRunnable
        )

        keepReading = false

        try {
            unregisterReceiver(
                usbReceiver
            )
        } catch (_: Exception) {
        }

        disconnect()

        super.onDestroy()
    }
}
