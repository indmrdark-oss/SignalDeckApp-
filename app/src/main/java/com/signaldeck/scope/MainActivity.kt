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
import android.text.SpannableString
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

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

    private val ACTION_USB_PERMISSION = "com.signaldeck.scope.USB_PERMISSION"

    private var capturing = false
    private var capRate = 0.0
    private var capSamples: IntArray? = null

    private var lineBuffer = StringBuilder()
    private var lastMeasuredHz = 0.0
    private var lastDuty = 50.0
    private var lastWaveform = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private var liveCaptureActive = false
    private var lastFrameArrivedMs = 0L

    // --- Dial-controlled frequency state ---
    private var dialFrequency = 1000.0
    private val F_MIN = 1.0
    private val F_MAX = 20000.0
    // Coarse: one full 360deg rotation = 3000Hz change. Fine: one full
    // rotation = 60Hz change - slow, deliberate drag at fine speed can
    // genuinely land on an exact integer Hz.
    private var hzPerDegree = 3000.0 / 360.0
    private var pendingSend = false
    private val sendThrottleMs = 60L

    private val liveCaptureLoop = object : Runnable {
        override fun run() {
            if (!liveCaptureActive) return
            if (!capturing) serial?.writeLine("C")
            uiHandler.postDelayed(this, 400L)
        }
    }

    private val throttledSender = object : Runnable {
        override fun run() {
            if (pendingSend) {
                serial?.writeLine("F%.2f".format(dialFrequency))
                pendingSend = false
            }
            uiHandler.postDelayed(this, sendThrottleMs)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    ACTION_USB_PERMISSION -> {
                        synchronized(this) {
                            val device: UsbDevice? =
                                if (Build.VERSION.SDK_INT >= 33)
                                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                                else
                                    @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            if (granted && device != null) connectToDevice(device)
                            else appendLog("Permission denied for USB device.")
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> appendLog("Arduino attached. Tap Connect.")
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> { appendLog("Device detached."); disconnect() }
                }
            } catch (e: Exception) {
                appendLog("CRASH in usbReceiver: " + e.toString())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        scopeView = findViewById(R.id.scopeView)
        dialView = findViewById(R.id.dialView)
        connStatus = findViewById(R.id.connStatus)
        connectBtn = findViewById(R.id.connectBtn)
        liveCaptureBtn = findViewById(R.id.liveCaptureBtn)
        reconBtn = findViewById(R.id.reconBtn)
        cmdInput = findViewById(R.id.cmdInput)
        sendBtn = findViewById(R.id.sendBtn)
        logView = findViewById(R.id.logView)
        clearLogBtn = findViewById(R.id.clearLogBtn)
        rTarget = findViewById(R.id.rTarget)
        rTargetBig = findViewById(R.id.rTargetBig)
        rMeasured = findViewById(R.id.rMeasured)
        rDuty = findViewById(R.id.rDuty)
        rRate = findViewById(R.id.rRate)
        rVoltage = findViewById(R.id.rVoltage)
        coarseModeBtn = findViewById(R.id.coarseModeBtn)
        fineModeBtn = findViewById(R.id.fineModeBtn)
        minus1Btn = findViewById(R.id.minus1Btn)
        minus01Btn = findViewById(R.id.minus01Btn)
        plus01Btn = findViewById(R.id.plus01Btn)
        plus1Btn = findViewById(R.id.plus1Btn)

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        connectBtn.setOnClickListener {
            try { if (serial != null) disconnect() else requestDevice() }
            catch (e: Exception) { appendLog("CRASH on Connect tap: " + e.toString()) }
        }

        liveCaptureBtn.setOnClickListener {
            if (liveCaptureActive) {
                liveCaptureActive = false
                liveCaptureBtn.text = "Start Live Capture"
                appendLog("Live capture stopped.")
            } else {
                liveCaptureActive = true
                liveCaptureBtn.text = "Stop Live Capture"
                appendLog("Live capture started.")
                uiHandler.post(liveCaptureLoop)
            }
        }

        reconBtn.setOnClickListener {
            liveCaptureActive = false
            liveCaptureBtn.text = "Start Live Capture"
            rVoltage.text = "Voltage: -- (no real capture yet)"
            scopeView.showReconstructed()
        }

        clearLogBtn.setOnClickListener { logView.text = "" }

        sendBtn.setOnClickListener {
            val text = cmdInput.text.toString().trim()
            if (text.isNotEmpty()) {
                appendCommandLog(text)
                serial?.writeLine(text)
                cmdInput.setText("")
            }
        }

        // --- Dial wiring ---
        dialView.onRotate = { deltaDegrees ->
            val deltaHz = deltaDegrees * hzPerDegree
            dialFrequency = (dialFrequency + deltaHz).coerceIn(F_MIN, F_MAX)
            updateDialDisplay()
            pendingSend = true
        }

        coarseModeBtn.setOnClickListener {
            hzPerDegree = 3000.0 / 360.0
            appendLog("Dial: COARSE mode (1 turn ~= 3000 Hz)")
        }
        fineModeBtn.setOnClickListener {
            hzPerDegree = 60.0 / 360.0
            appendLog("Dial: FINE mode (1 turn ~= 60 Hz - use this for exact 1Hz steps)")
        }

        minus1Btn.setOnClickListener { nudgeFrequency(-1.0) }
        minus01Btn.setOnClickListener { nudgeFrequency(-0.1) }
        plus01Btn.setOnClickListener { nudgeFrequency(0.1) }
        plus1Btn.setOnClickListener { nudgeFrequency(1.0) }

        updateDialDisplay()
        uiHandler.post(throttledSender)
    }

    private fun nudgeFrequency(deltaHz: Double) {
        dialFrequency = (dialFrequency + deltaHz).coerceIn(F_MIN, F_MAX)
        updateDialDisplay()
        // Nudge buttons send immediately, not throttled - these ARE the
        // guaranteed-exact path since they don't depend on touch precision.
        serial?.writeLine("F%.2f".format(dialFrequency))
    }

    private fun updateDialDisplay() {
        rTargetBig.text = "%.2f Hz".format(dialFrequency)
    }

    private fun requestDevice() {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            appendLog("No USB device found. Check the cable and that the Arduino is plugged in.")
            return
        }
        val device = devices.first()
        val usbPermissionIntent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(this, 0, usbPermissionIntent, flags)
        if (usbManager.hasPermission(device)) connectToDevice(device)
        else usbManager.requestPermission(device, permissionIntent)
    }

    private fun connectToDevice(device: UsbDevice) {
        try {
            val mgr = UsbSerialManager(usbManager, device)
            val opened = mgr.open(250000)
            if (!opened) { appendLog("Failed to open device as CDC-ACM serial."); return }
            serial = mgr
            connStatus.text = "Connected · 250000 baud"
            connStatus.setTextColor(0xFF4DFFA0.toInt())
            startReadLoop()
            // Push current dial value to the Arduino on connect, so both
            // sides agree on frequency from the start.
            serial?.writeLine("F%.2f".format(dialFrequency))
        } catch (e: Exception) {
            appendLog("CRASH in connectToDevice: " + e.toString())
        }
    }

    private fun disconnect() {
        keepReading = false
        liveCaptureActive = false
        liveCaptureBtn.text = "Start Live Capture"
        readThread = null
        serial?.close()
        serial = null
        connStatus.text = "Not connected"
        connStatus.setTextColor(0xFFFF5A5A.toInt())
    }

    private fun startReadLoop() {
        keepReading = true
        readThread = Thread {
            val buf = ByteArray(512)
            while (keepReading) {
                val n = try { serial?.read(buf, 200) ?: -1 } catch (e: Exception) { -1 }
                if (n > 0) {
                    val chunk = String(buf, 0, n, Charsets.US_ASCII)
                    lineBuffer.append(chunk)
                    var idx: Int
                    while (lineBuffer.indexOf("\n").also { idx = it } >= 0) {
                        val line = lineBuffer.substring(0, idx).trim()
                        lineBuffer.delete(0, idx + 1)
                        runOnUiThread { handleLine(line) }
                    }
                }
            }
        }
        readThread?.start()
    }

    private fun handleLine(line: String) {
        if (line.startsWith("AI>")) { appendLog(line); return }
        if (line.startsWith("CAP,")) {
            val parts = line.split(",")
            capturing = true
            capRate = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            capSamples = null
            return
        }
        if (capturing) {
            if (line == "ENDCAP") { capturing = false; onCaptureComplete(); return }
            if (line.isNotEmpty() && line.matches(Regex("^[0-9,]+$"))) {
                capSamples = line.split(",").map { it.toIntOrNull() ?: 0 }.toIntArray()
                return
            }
        }
        if (line.startsWith("Target:")) { parseStatusLine(line); return }
        appendLog(line)
    }

    private fun parseStatusLine(line: String) {
        val noSignal = line.contains("NO SIGNAL")
        if (!noSignal) {
            Regex("Measured:\\s*([\\d.]+)").find(line)?.let { lastMeasuredHz = it.groupValues[1].toDouble() }
        }
        Regex("Duty:\\s*([\\d.]+)").find(line)?.let { lastDuty = it.groupValues[1].toDouble() }
        lastWaveform = !noSignal

        rTarget.text = "Target: %.2f Hz".format(dialFrequency)
        rMeasured.text = if (lastWaveform) "Measured: %.2f Hz".format(lastMeasuredHz) else "Measured: NO SIGNAL"
        rDuty.text = "Duty: %.1f%%".format(lastDuty)

        scopeView.liveFreq = if (lastWaveform) lastMeasuredHz else dialFrequency
        scopeView.liveDuty = lastDuty
        scopeView.waveformPresent = lastWaveform
    }

    private fun onCaptureComplete() {
        val samples = capSamples ?: return
        val freq = if (lastWaveform) lastMeasuredHz else dialFrequency
        val spc = if (freq > 0) capRate / freq else 0.0

        val now = System.currentTimeMillis()
        val fps = if (lastFrameArrivedMs > 0) 1000.0 / (now - lastFrameArrivedMs) else 0.0
        lastFrameArrivedMs = now
        if (liveCaptureActive) rRate.text = "Real frame rate: %.1f fps".format(fps)

        if (samples.isNotEmpty()) {
            val minV = (samples.min() / 255.0) * 5.0
            val maxV = (samples.max() / 255.0) * 5.0
            val avgV = (samples.average() / 255.0) * 5.0
            rVoltage.text = "Voltage: min %.2fV | max %.2fV | avg %.2fV | Vpp %.2fV (real ADC readings)"
                .format(minV, maxV, avgV, maxV - minV)
        }

        val label = when {
            spc >= 10 -> "CAPTURED · HIGH FIDELITY (%.1f samples/cycle)".format(spc)
            spc >= 4 -> "CAPTURED · REDUCED DETAIL (%.1f samples/cycle)".format(spc)
            else -> "TOO FAST TO CAPTURE - SWITCH TO RECONSTRUCTED"
        }

        if (spc >= 4) scopeView.showCaptured(samples, label, capRate)
        else scopeView.showReconstructed()
    }

    private fun appendLog(line: String) {
        runOnUiThread { logView.append(line + "\n") }
    }

    private fun appendCommandLog(text: String) {
        runOnUiThread {
            val display = ">> $text\n"
            val spannable = SpannableString(display)
            spannable.setSpan(ForegroundColorSpan(Color.RED), 0, display.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(Typeface.BOLD), 0, display.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            logView.append(spannable)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        keepReading = false
        liveCaptureActive = false
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        serial?.close()
    }
}
