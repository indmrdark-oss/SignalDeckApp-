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
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private var serial: UsbSerialManager? = null

    private var readThread: Thread? = null
    @Volatile private var keepReading = false

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

    private var lineBuffer = StringBuilder()

    private var lastMeasuredHz = 0.0
    private var lastDuty = 50.0
    private var lastWaveform = false

    private val uiHandler =
        Handler(Looper.getMainLooper())

    private var liveCaptureActive = false
    private var lastFrameArrivedMs = 0L

    // =========================================================
    // FREQUENCY DIAL
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
     * Hold arrow:
     *
     * First change happens immediately.
     * Then wait 300 ms.
     * Then change by 1 Hz every 100 ms.
     *
     * = about 10 Hz/second.
     */
    private val freqRepeatRunnable =
        object : Runnable {

            override fun run() {

                if (freqRepeatDirection == 0)
                    return

                changeFrequency(1.0 * freqRepeatDirection)

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

                if (!liveCaptureActive)
                    return

                if (!capturing)
                    serial?.writeLine("C")

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

                    sendFrequency()

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

                            synchronized(this) {

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
                        "CRASH in usbReceiver: ${e}"
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

        // =====================================================
        // USB BROADCAST
        // =====================================================

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

        // =====================================================
        // CONNECT
        // =====================================================

        connectBtn.setOnClickListener {

            try {

                if (serial != null) {
                    disconnect()
                } else {
                    requestDevice()
                }

            } catch (e: Exception) {

                appendLog(
                    "CRASH on Connect tap: $e"
                )
            }
        }

        // =====================================================
        // LIVE CAPTURE
        // =====================================================

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

        // =====================================================
        // RECONSTRUCTED
        // =====================================================

        reconBtn.setOnClickListener {

            liveCaptureActive = false

            liveCaptureBtn.text =
                "Start Live Capture"

            rVoltage.text =
                "Voltage: -- (no real capture yet)"

            scopeView.showReconstructed()
        }

        // =====================================================
        // CLEAR LOG
        // =====================================================

        clearLogBtn.setOnClickListener {
            logView.text = ""
        }

        // =====================================================
        // MANUAL COMMAND
        // =====================================================

        sendBtn.setOnClickListener {

            val text =
                cmdInput.text.toString().trim()

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

        // =====================================================
        // COARSE MODE
        // =====================================================

        coarseModeBtn.setOnClickListener {

            hzPerDegree =
                3000.0 / 360.0

            appendLog(
                "Dial: COARSE mode — 1 rotation ≈ 3000 Hz"
            )
        }

        // =====================================================
        // FINE MODE
        // =====================================================

        fineModeBtn.setOnClickListener {

            hzPerDegree =
                60.0 / 360.0

            appendLog(
                "Dial: FINE mode — 1 rotation ≈ 60 Hz"
            )
        }

        // =====================================================
        // DOWN ARROW — HOLD
        // =====================================================

        downFreqBtn.setOnTouchListener {
                _, event ->

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {

                    freqRepeatDirection = -1

                    // Immediate first step
                    changeFrequency(-1.0)

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
        // UP ARROW — HOLD
        // =====================================================

        upFreqBtn.setOnTouchListener {
                _, event ->

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {

                    freqRepeatDirection = 1

                    // Immediate first step
                    changeFrequency(1.0)

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
        // EXACT NUDGE BUTTONS
        // =====================================================

        minus1Btn.setOnClickListener {
            changeFrequency(-1.0)
        }

        minus01Btn.setOnClickListener {
            changeFrequency(-0.1)
        }

        plus01Btn.setOnClickListener {
            changeFrequency(0.1)
        }

        plus1Btn.setOnClickListener {
            changeFrequency(1.0)
        }

        // =====================================================
        // INITIALIZE
        // =====================================================

        updateDialDisplay()

        uiHandler.post(
            throttledSender
        )
    }

    // =========================================================
    // FREQUENCY CHANGE
    // =========================================================

    private fun changeFrequency(
        delta: Double
    ) {

        dialFrequency =
            (
                dialFrequency + delta
            ).coerceIn(
                F_MIN,
                F_MAX
            )

        updateDialDisplay()

        sendFrequency()
    }

    // =========================================================
    // SEND FREQUENCY TO ARDUINO
    // =========================================================

    private fun sendFrequency() {

        val command =
            String.format(
                Locale.US,
                "F%.2f",
                dialFrequency
            )

        serial?.writeLine(command)
    }

    // =========================================================
    // START ARROW REPEAT
    // =========================================================

    private fun startFrequencyRepeat() {

        uiHandler.removeCallbacks(
            freqRepeatRunnable
        )

        /*
         * 300 ms delay before repeating.
         * This prevents accidental rapid jumps.
         */
        uiHandler.postDelayed(
            freqRepeatRunnable,
            300L
        )
    }

    // =========================================================
    // STOP ARROW REPEAT
    // =========================================================

    private fun stopFrequencyRepeat() {

        freqRepeatDirection = 0

        uiHandler.removeCallbacks(
            freqRepeatRunnable
        )
    }

    // =========================================================
    // DIAL DISPLAY
    // =========================================================

    private fun updateDialDisplay() {

        rTargetBig.text =
            String.format(
                Locale.US,
                "%.2f Hz",
                dialFrequency
            )

        rTarget.text =
            String.format(
                Locale.US,
                "Target: %.2f Hz",
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
                    "Check the cable and that Arduino is plugged in."
            )

            return
        }

        val device =
            devices.first()

        /*
         * Explicitly construct Intent with a String.
         * This avoids the Kotlin overload ambiguity.
         */
        val usbPermissionIntent =
            Intent(ACTION_USB_PERMISSION).apply {
                setPackage(packageName)
            }

        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }

        val permissionIntent =
            PendingIntent.getBroadcast(
                this,
                0,
                usbPermissionIntent,
                flags
            )

        if (
            usbManager.hasPermission(device)
        ) {

            connectToDevice(device)

        } else {

            usbManager.requestPermission(
                device,
                permissionIntent
            )
        }
    }

    // =========================================================
    // CONNECT TO ARDUINO
    // =========================================================

    private fun connectToDevice(
        device: UsbDevice
    ) {

        try {

            val mgr =
                UsbSerialManager(
                    usbManager,
                    device
                )

            val opened =
                mgr.open(250000)

            if (!opened) {

                appendLog(
                    "Failed to open device as CDC-ACM serial."
                )

                return
            }

            serial = mgr

            connStatus.text =
                "Connected · 250000 baud"

            connStatus.setTextColor(
                0xFF4DFFA0.toInt()
            )

            appendLog(
                "USB serial connected."
            )

            startReadLoop()

            // Synchronize Arduino with current frequency
            sendFrequency()

        } catch (e: Exception) {

            appendLog(
                "CRASH in connectToDevice: $e"
            )
        }
    }

    // =========================================================
    // DISCONNECT
    // =========================================================

    private fun disconnect() {

        keepReading = false

        liveCaptureActive = false

        stopFrequencyRepeat()

        liveCaptureBtn.text =
            "Start Live Capture"

        readThread = null

        try {
            serial?.close()
        } catch (_: Exception) {
        }

        serial = null

        connStatus.text =
            "Not connected"

        connStatus.setTextColor(
            0xFFFF5A5A.toInt()
        )
    }

    // =========================================================
    // SERIAL READ LOOP
    // =========================================================

    private fun startReadLoop() {

        keepReading = true

        readThread =
            Thread {

                val buf =
                    ByteArray(512)

                while (keepReading) {

                    val n =
                        try {

                            serial?.read(
                                buf,
                                200
                            ) ?: -1

                        } catch (
                            e: Exception
                        ) {

                            -1
                        }

                    if (n > 0) {

                        val chunk =
                            String(
                                buf,
                                0,
                                n,
                                Charsets.US_ASCII
                            )

                        lineBuffer.append(
                            chunk
                        )

                        var idx: Int

                        while (
                            lineBuffer
                                .indexOf("\n")
                                .also { idx = it } >= 0
                        ) {

                            val line =
                                lineBuffer
                                    .substring(
                                        0,
                                        idx
                                    )
                                    .trim()

                            lineBuffer.delete(
                                0,
                                idx + 1
                            )

                            runOnUiThread {
                                handleLine(line)
                            }
                        }
                    }
                }
            }

        readThread?.start()
    }

    // =========================================================
    // HANDLE SERIAL LINE
    // =========================================================

    private fun handleLine(
        line: String
    ) {

        if (line.startsWith("AI>")) {

            appendLog(line)
            return
        }

        if (line.startsWith("CAP,")) {

            val parts =
                line.split(",")

            capturing = true

            capRate =
                parts
                    .getOrNull(2)
                    ?.toDoubleOrNull()
                    ?: 0.0

            capSamples = null

            return
        }

        if (capturing) {

            if (line == "ENDCAP") {

                capturing = false

                onCaptureComplete()

                return
            }

            if (
                line.isNotEmpty() &&
                line.matches(
                    Regex("^[0-9,]+$")
                )
            ) {

                capSamples =
                    line
                        .split(",")
                        .map {
                            it.toIntOrNull() ?: 0
                        }
                        .toIntArray()

                return
            }
        }

        if (line.startsWith("Target:")) {

            parseStatusLine(line)

            return
        }

        appendLog(line)
    }

    // =========================================================
    // PARSE STATUS
    // =========================================================

    private fun parseStatusLine(
        line: String
    ) {

        val noSignal =
            line.contains("NO SIGNAL")

        if (!noSignal) {

            Regex(
                "Measured:\\s*([\\d.]+)"
            ).find(line)?.let {

                lastMeasuredHz =
                    it.groupValues[1]
                        .toDouble()
            }
        }

        Regex(
            "Duty:\\s*([\\d.]+)"
        ).find(line)?.let {

            lastDuty =
                it.groupValues[1]
                    .toDouble()
        }

        lastWaveform =
            !noSignal

        rTarget.text =
            String.format(
                Locale.US,
                "Target: %.2f Hz",
                dialFrequency
            )

        rMeasured.text =
            if (lastWaveform) {

                String.format(
                    Locale.US,
                    "Measured: %.2f Hz",
                    lastMeasuredHz
                )

            } else {

                "Measured: NO SIGNAL"
            }

        rDuty.text =
            String.format(
                Locale.US,
                "Duty: %.1f%%",
                lastDuty
            )

        scopeView.liveFreq =
            if (lastWaveform)
                lastMeasuredHz
            else
                dialFrequency

        scopeView.liveDuty =
            lastDuty

        scopeView.waveformPresent =
            lastWaveform
    }

    // =========================================================
    // CAPTURE COMPLETE
    // =========================================================

    private fun onCaptureComplete() {

        val samples =
            capSamples ?: return

        val freq =
            if (lastWaveform)
                lastMeasuredHz
            else
                dialFrequency

        val spc =
            if (freq > 0)
                capRate / freq
            else
                0.0

        val now =
            System.currentTimeMillis()

        val fps =
            if (lastFrameArrivedMs > 0) {

                1000.0 /
                    (now - lastFrameArrivedMs)

            } else {

                0.0
            }

        lastFrameArrivedMs = now

        if (liveCaptureActive) {

            rRate.text =
                String.format(
                    Locale.US,
                    "Real frame rate: %.1f fps",
                    fps
                )
        }

        if (samples.isNotEmpty()) {

            val minV =
                (samples.min() / 255.0) * 5.0

            val maxV =
                (samples.max() / 255.0) * 5.0

            val avgV =
                (samples.average() / 255.0) * 5.0

            rVoltage.text =
                String.format(
                    Locale.US,
                    "Voltage: min %.2fV | max %.2fV | avg %.2fV | Vpp %.2fV (real ADC readings)",
                    minV,
                    maxV,
                    avgV,
                    maxV - minV
                )
        }

        val label =
            when {

                spc >= 10 ->
                    String.format(
                        Locale.US,
                        "CAPTURED · HIGH FIDELITY (%.1f samples/cycle)",
                        spc
                    )

                spc >= 4 ->
                    String.format(
                        Locale.US,
                        "CAPTURED · REDUCED DETAIL (%.1f samples/cycle)",
                        spc
                    )

                else ->
                    "TOO FAST TO CAPTURE - SWITCH TO RECONSTRUCTED"
            }

        if (spc >= 4) {

            scopeView.showCaptured(
                samples,
                label,
                capRate
            )

        } else {

            scopeView.showReconstructed()
        }
    }

    // =========================================================
    // LOGGING
    // =========================================================

    private fun appendLog(
        line: String
    ) {

        runOnUiThread {

            logView.append(
                line + "\n"
            )
        }
    }

    private fun appendCommandLog(
        text: String
    ) {

        runOnUiThread {

            val display =
                ">> $text\n"

            val spannable =
                SpannableString(
                    display
                )

            spannable.setSpan(
                ForegroundColorSpan(
                    Color.RED
                ),
                0,
                display.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            spannable.setSpan(
                StyleSpan(
                    Typeface.BOLD
                ),
                0,
                display.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            logView.append(
                spannable
            )
        }
    }

    // =========================================================
    // CLEANUP
    // =========================================================

    override fun onPause() {

        super.onPause()

        stopFrequencyRepeat()
    }

    override fun onDestroy() {

        stopFrequencyRepeat()

        uiHandler.removeCallbacks(
            liveCaptureLoop
        )

        uiHandler.removeCallbacks(
            throttledSender
        )

        keepReading = false

        liveCaptureActive = false

        try {
            unregisterReceiver(
                usbReceiver
            )
        } catch (_: Exception) {
        }

        try {
            serial?.close()
        } catch (_: Exception) {
        }

        serial = null

        super.onDestroy()
    }
}
