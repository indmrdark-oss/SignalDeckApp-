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
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private var serial: UsbSerialManager? = null

    private lateinit var scopeView: ScopeView
    private lateinit var dialView: DialView

    private lateinit var connStatus: TextView
    private lateinit var connectBtn: Button
    private lateinit var liveCaptureBtn: Button
    private lateinit var reconBtn: Button

    private lateinit var zoomInBtn: Button
    private lateinit var zoomOutBtn: Button
    private lateinit var resetZoomBtn: Button

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

    private lateinit var speed1xBtn: Button
    private lateinit var speed2xBtn: Button

    private val ACTION_USB_PERMISSION = "com.signaldeck.scope.USB_PERMISSION"
    private val BAUD_RATE = 250000

    private val handler = Handler(Looper.getMainLooper())
    private var readThread: Thread? = null

    @Volatile private var keepReading = false

    private var dialFrequency = 1000.0
    private val F_MIN = 1.0
    private val F_MAX = 20000.0
    private var hzPerDegree = 8000.0 / 360.0

    private var arrowSpeed = 1
    private var repeatDirection = 0
    private var liveCapture = false

    private var capturing = false
    private var capRate = 0.0
    private var capSamples: IntArray? = null
    private var lastMeasuredHz = 0.0
    private var lastWaveform = false

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (repeatDirection == 0) return
            changeFrequencyFromArrow()
            val delay = if (arrowSpeed == 1) 100L else 50L
            handler.postDelayed(this, delay)
        }
    }

    private val liveCaptureLoop = object : Runnable {
        override fun run() {
            if (!liveCapture) return
            if (!capturing) serial?.writeLine("C")
            handler.postDelayed(this, 400L)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        appendLog("USB permission granted.")
                        connectToDevice(device)
                    } else {
                        appendLog("USB permission denied.")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> appendLog("USB device attached. Tap Connect.")
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { appendLog("USB device detached."); disconnect() }
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

        zoomInBtn = findViewById(R.id.zoomInBtn)
        zoomOutBtn = findViewById(R.id.zoomOutBtn)
        resetZoomBtn = findViewById(R.id.resetZoomBtn)

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

        downFreqBtn = findViewById(R.id.downFreqBtn)
        upFreqBtn = findViewById(R.id.upFreqBtn)

        speed1xBtn = findViewById(R.id.speed1xBtn)
        speed2xBtn = findViewById(R.id.speed2xBtn)

        setupUsbReceiver()
        setupButtons()
        setupDial()

        updateDialDisplay()

        appendLog("SignalDeck Scope ready.")
        appendLog("Serial baud: $BAUD_RATE")
    }

    private fun setupUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun setupButtons() {
        connectBtn.setOnClickListener {
            if (serial != null) disconnect() else requestDevice()
        }

        clearLogBtn.setOnClickListener { logView.text = "" }

        sendBtn.setOnClickListener {
            val command = cmdInput.text.toString().trim()
            if (command.isNotEmpty()) {
                appendCommandLog(command)
                serial?.writeLine(command)
                cmdInput.setText("")
            }
        }

        liveCaptureBtn.setOnClickListener {
            liveCapture = !liveCapture
            if (liveCapture) {
                liveCaptureBtn.text = "Stop Live Capture"
                appendLog("Live capture started.")
                handler.post(liveCaptureLoop)
            } else {
                liveCaptureBtn.text = "Start Live Capture"
                appendLog("Live capture stopped.")
            }
        }

        reconBtn.setOnClickListener {
            liveCapture = false
            liveCaptureBtn.text = "Start Live Capture"
            scopeView.showReconstructed()
            rVoltage.text = "Voltage: --"
            appendLog("Showing reconstructed waveform.")
        }

        zoomInBtn.setOnClickListener { scopeView.zoomIn() }
        zoomOutBtn.setOnClickListener { scopeView.zoomOut() }
        resetZoomBtn.setOnClickListener { scopeView.resetZoom() }

        coarseModeBtn.setOnClickListener {
            hzPerDegree = 8000.0 / 360.0
            appendLog("Dial: COARSE — 1 rotation ≈ 8000 Hz")
        }

        fineModeBtn.setOnClickListener {
            hzPerDegree = 300.0 / 360.0
            appendLog("Dial: FINE — 1 rotation ≈ 300 Hz")
        }

        speed1xBtn.setOnClickListener { arrowSpeed = 1; appendLog("Arrow speed: 1×") }
        speed2xBtn.setOnClickListener { arrowSpeed = 2; appendLog("Arrow speed: 2×") }

        minus1Btn.setOnClickListener { nudgeFrequency(-1.0) }
        minus01Btn.setOnClickListener { nudgeFrequency(-0.1) }
        plus01Btn.setOnClickListener { nudgeFrequency(0.1) }
        plus1Btn.setOnClickListener { nudgeFrequency(1.0) }

        setupArrowButton(downFreqBtn, -1)
        setupArrowButton(upFreqBtn, 1)
    }

    private fun setupArrowButton(button: Button, direction: Int) {
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    repeatDirection = direction
                    changeFrequencyFromArrow()
                    handler.removeCallbacks(repeatRunnable)
                    handler.postDelayed(repeatRunnable, 250L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatDirection = 0
                    handler.removeCallbacks(repeatRunnable)
                    true
                }
                else -> true
            }
        }
    }

    private fun setupDial() {
        dialView.onRotate = { degrees ->
            val change = degrees * hzPerDegree
            dialFrequency = (dialFrequency + change).coerceIn(F_MIN, F_MAX)
            updateDialDisplay()
            sendFrequency()
        }
    }

    private fun changeFrequencyFromArrow() {
        val step = if (arrowSpeed == 1) 1.0 else 5.0
        dialFrequency = (dialFrequency + repeatDirection * step).coerceIn(F_MIN, F_MAX)
        updateDialDisplay()
        sendFrequency()
    }

    private fun nudgeFrequency(amount: Double) {
        dialFrequency = (dialFrequency + amount).coerceIn(F_MIN, F_MAX)
        updateDialDisplay()
        sendFrequency()
    }

    private fun updateDialDisplay() {
        val value = String.format(Locale.US, "%.2f Hz", dialFrequency)
        rTargetBig.text = value
        rTarget.text = "Target: $value"
        dialView.currentFrequency = dialFrequency
        dialView.invalidate()
    }

    private fun sendFrequency() {
        val command = String.format(Locale.US, "F%.2f", dialFrequency)
        serial?.writeLine(command)
    }

    private fun requestDevice() {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) { appendLog("No USB device found."); return }
        val device = devices.first()
        if (usbManager.hasPermission(device)) { connectToDevice(device); return }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
        usbManager.requestPermission(device, permissionIntent)
        appendLog("Requesting USB permission...")
    }

    private fun connectToDevice(device: UsbDevice) {
        try {
            val manager = UsbSerialManager(usbManager, device)
            val opened = manager.open(BAUD_RATE)
            if (!opened) {
                appendLog("Could not open USB serial port.")
                manager.close()
                return
            }
            serial = manager
            connStatus.text = "Connected"
            connectBtn.text = "Disconnect"
            appendLog("USB serial connected.")
            appendLog(manager.debugInInfo())
            startReading()
            sendFrequency()
        } catch (e: Exception) {
            serial = null
            connStatus.text = "Disconnected"
            connectBtn.text = "Connect"
            appendLog("Connection failed: ${e.message}")
        }
    }

    private fun startReading() {
        if (keepReading) return
        keepReading = true
        readThread = Thread {
            val buffer = ByteArray(4096)
            val lineBuilder = StringBuilder()
            while (keepReading) {
                try {
                    val count = serial?.read(buffer, 100) ?: -1
                    if (count > 0) {
                        val chunk = String(buffer, 0, count, Charsets.US_ASCII)
                        lineBuilder.append(chunk)
                        while (true) {
                            val newlineIndex = lineBuilder.indexOf("\n")
                            if (newlineIndex < 0) break
                            val line = lineBuilder.substring(0, newlineIndex).trim()
                            lineBuilder.delete(0, newlineIndex + 1)
                            if (line.isNotEmpty()) {
                                handler.post { processSerialLine(line) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (keepReading) {
                        handler.post { appendLog("Read error: ${e.message}") }
                    }
                    break
                }
            }
        }
        readThread?.start()
    }

    private fun processSerialLine(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return

        if (text.startsWith("AI>")) {
            appendStyledLog(text, Color.CYAN)
            return
        }

        if (text.startsWith("CAP,")) {
            val parts = text.split(",")
            capturing = true
            capRate = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            capSamples = null
            appendSerialLog(text)
            return
        }
        if (capturing) {
            if (text == "ENDCAP") {
                capturing = false
                onCaptureComplete()
                return
            }
            if (text.matches(Regex("^[0-9,]+$"))) {
                capSamples = text.split(",").mapNotNull { it.toIntOrNull() }.toIntArray()
                return
            }
        }

        if (text.startsWith("Target:")) {
            appendSerialLog(text)
            val noSignal = text.contains("NO SIGNAL")
            Regex("Measured:\\s*([\\d.]+)").find(text)?.let {
                if (!noSignal) lastMeasuredHz = it.groupValues[1].toDouble()
            }
            Regex("Duty:\\s*([\\d.]+)").find(text)?.let {
                rDuty.text = "Duty: ${it.groupValues[1]}%"
                scopeView.liveDuty = it.groupValues[1].toDouble()
            }
            lastWaveform = !noSignal
            rMeasured.text = if (lastWaveform) String.format(Locale.US, "Measured: %.2f Hz", lastMeasuredHz)
                              else "Measured: NO SIGNAL"
            rRate.text = if (lastWaveform) "Signal present" else "No signal"

            scopeView.liveFreq = if (lastWaveform) lastMeasuredHz else dialFrequency
            scopeView.waveformPresent = lastWaveform
            if (!liveCapture && scopeView.mode != "captured") scopeView.showReconstructed()
            return
        }

        appendSerialLog(text)
    }

    private fun onCaptureComplete() {
        val samples = capSamples ?: return
        val freq = if (lastWaveform) lastMeasuredHz else dialFrequency
        val spc = if (freq > 0) capRate / freq else 0.0

        if (samples.isNotEmpty()) {
            val minV = (samples.min() / 255.0) * 5.0
            val maxV = (samples.max() / 255.0) * 5.0
            val avgV = (samples.average() / 255.0) * 5.0
            rVoltage.text = String.format(Locale.US, "Voltage: min %.2fV max %.2fV avg %.2fV (real ADC)", minV, maxV, avgV)
        }

        val label = when {
            spc >= 10 -> "CAPTURED - HIGH FIDELITY"
            spc >= 4 -> "CAPTURED - REDUCED DETAIL"
            else -> "TOO FAST - RECONSTRUCTED"
        }

        if (spc >= 4) {
            scopeView.showCaptured(samples, label, capRate)
        } else {
            scopeView.showReconstructed()
        }
    }

    private fun disconnect() {
        keepReading = false
        repeatDirection = 0
        liveCapture = false
        handler.removeCallbacks(repeatRunnable)
        try { readThread?.interrupt() } catch (_: Exception) {}
        readThread = null
        try { serial?.close() } catch (_: Exception) {}
        serial = null
        connStatus.text = "Disconnected"
        connectBtn.text = "Connect"
        appendLog("Disconnected.")
    }

    private fun appendLog(message: String) {
        handler.post {
            val current = logView.text.toString()
            logView.text = if (current.isEmpty()) message else "$current\n$message"
        }
    }

    private fun appendCommandLog(command: String) {
        appendStyledLog(">> $command", Color.RED)
    }

    private fun appendSerialLog(message: String) {
        appendStyledLog("<< $message", Color.GREEN)
    }

    private fun appendStyledLog(message: String, color: Int) {
        val text = SpannableString("$message\n")
        text.setSpan(ForegroundColorSpan(color), 0, message.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        logView.append(text)
    }

    override fun onDestroy() {
        repeatDirection = 0
        handler.removeCallbacks(repeatRunnable)
        keepReading = false
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        disconnect()
        super.onDestroy()
    }
}
