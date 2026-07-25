package com.signaldeck.scope

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
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
    private lateinit var connStatus: TextView
    private lateinit var connectBtn: Button
    private lateinit var captureBtn: Button
    private lateinit var liveBtn: Button
    private lateinit var cmdInput: EditText
    private lateinit var sendBtn: Button
    private lateinit var logView: TextView
    private lateinit var rTarget: TextView
    private lateinit var rMeasured: TextView
    private lateinit var rDuty: TextView

    private val ACTION_USB_PERMISSION = "com.signaldeck.scope.USB_PERMISSION"

    private var capturing = false
    private var capN = 0
    private var capRate = 0.0
    private var capSamples: IntArray? = null

    private var lineBuffer = StringBuilder()
    private var lastTargetHz = 0.0
    private var lastMeasuredHz = 0.0
    private var lastDuty = 50.0
    private var lastWaveform = false

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
                            if (granted && device != null) {
                                connectToDevice(device)
                            } else {
                                appendLog("Permission denied for USB device.")
                            }
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        appendLog("Arduino attached. Tap Connect.")
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        appendLog("Device detached.")
                        disconnect()
                    }
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
        connStatus = findViewById(R.id.connStatus)
        connectBtn = findViewById(R.id.connectBtn)
        captureBtn = findViewById(R.id.captureBtn)
        liveBtn = findViewById(R.id.liveBtn)
        cmdInput = findViewById(R.id.cmdInput)
        sendBtn = findViewById(R.id.sendBtn)
        logView = findViewById(R.id.logView)
        rTarget = findViewById(R.id.rTarget)
        rMeasured = findViewById(R.id.rMeasured)
        rDuty = findViewById(R.id.rDuty)

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
            try {
                if (serial != null) disconnect() else requestDevice()
            } catch (e: Exception) {
                appendLog("CRASH on Connect tap: " + e.toString())
            }
        }
        captureBtn.setOnClickListener {
            appendLog("Requesting capture...")
            serial?.writeLine("C")
        }
        liveBtn.setOnClickListener {
            scopeView.showLive()
        }
        sendBtn.setOnClickListener {
            val text = cmdInput.text.toString().trim()
            if (text.isNotEmpty()) {
                serial?.writeLine(text)
                cmdInput.setText("")
            }
        }
    }

    private fun requestDevice() {
        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) {
            appendLog("No USB device found. Check the cable and that the Arduino is plugged in.")
            return
        }
        val device = devices.first()

        // Explicit intent targeting this exact app/receiver - required on Android 14+ (API 34)
        // when combined with FLAG_MUTABLE, per the new PendingIntent security rules.
        val usbPermissionIntent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, usbPermissionIntent, flags
        )
        if (usbManager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        try {
            val mgr = UsbSerialManager(usbManager, device)
            val opened = mgr.open(250000)
            if (!opened) {
                appendLog("Failed to open device as CDC-ACM serial.")
                return
            }
            serial = mgr
            connStatus.text = "Connected · 250000 baud"
            connStatus.setTextColor(0xFF4DFFA0.toInt())
            startReadLoop()
        } catch (e: Exception) {
            appendLog("CRASH in connectToDevice: " + e.toString())
        }
    }

    private fun disconnect() {
        keepReading = false
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
        if (line.startsWith("AI>")) {
            appendLog(line)
            return
        }
        if (line.startsWith("CAP,")) {
            val parts = line.split(",")
            capturing = true
            capN = parts.getOrNull(1)?.toIntOrNull() ?: 0
            capRate = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            capSamples = null
            return
        }
        if (capturing) {
            if (line == "ENDCAP") {
                capturing = false
                onCaptureComplete()
                return
            }
            if (line.isNotEmpty() && line.matches(Regex("^[0-9,]+$"))) {
                capSamples = line.split(",").map { it.toIntOrNull() ?: 0 }.toIntArray()
                return
            }
        }
        if (line.startsWith("Target:")) {
            parseStatusLine(line)
            return
        }
        appendLog(line)
    }

    private fun parseStatusLine(line: String) {
        Regex("Target:\\s*([\\d.]+)").find(line)?.let { lastTargetHz = it.groupValues[1].toDouble() }
        val noSignal = line.contains("NO SIGNAL")
        if (!noSignal) {
            Regex("Measured:\\s*([\\d.]+)").find(line)?.let { lastMeasuredHz = it.groupValues[1].toDouble() }
        }
        Regex("Duty:\\s*([\\d.]+)").find(line)?.let { lastDuty = it.groupValues[1].toDouble() }
        lastWaveform = !noSignal

        rTarget.text = "Target: %.2f Hz".format(lastTargetHz)
        rMeasured.text = if (lastWaveform) "Measured: %.2f Hz".format(lastMeasuredHz) else "Measured: NO SIGNAL"
        rDuty.text = "Duty: %.1f%%".format(lastDuty)

        scopeView.liveFreq = if (lastWaveform) lastMeasuredHz else lastTargetHz
        scopeView.liveDuty = lastDuty
        scopeView.waveformPresent = lastWaveform
    }

    private fun onCaptureComplete() {
        val samples = capSamples ?: return
        val freq = if (lastWaveform) lastMeasuredHz else lastTargetHz
        val spc = if (freq > 0) capRate / freq else 0.0
        val label = when {
            spc >= 10 -> "CAPTURED · HIGH FIDELITY (%.1f samples/cycle)".format(spc)
            spc >= 4 -> "CAPTURED · REDUCED DETAIL (%.1f samples/cycle)".format(spc)
            else -> "TOO FAST TO CAPTURE - SHOWING RECONSTRUCTION"
        }
        appendLog("Capture done. Real sample rate: %.1f sps".format(capRate))
        if (spc >= 4) {
            scopeView.showCaptured(samples, label)
        } else {
            scopeView.showLive()
        }
    }

    private fun appendLog(line: String) {
        runOnUiThread { logView.append(line + "\n") }
    }

    override fun onDestroy() {
        super.onDestroy()
        keepReading = false
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        serial?.close()
    }
}
