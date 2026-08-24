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
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private var serial: UsbSerialManager? = null

    private lateinit var scopeView: ScopeView
    private lateinit var dialView: DialView

    // navigation (drawer + screens)
    private lateinit var slideRoot: SlideFrameLayout
    private lateinit var mainPage: View
    private lateinit var logbookPage: View
    private lateinit var scrimView: View
    private lateinit var drawerView: View
    private lateinit var menuBtnMain: View
    private lateinit var menuBtnLog: View
    private lateinit var drawerMainOpt: LinearLayout
    private lateinit var drawerLogOpt: LinearLayout

    // logbook screen
    private lateinit var liveBtn: Button
    private lateinit var refreshFilesBtn: Button
    private lateinit var viewerTitle: TextView
    private lateinit var logbookView: TextView
    private lateinit var logbookScroll: ScrollView
    private lateinit var sessionsList: LinearLayout

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

    private lateinit var sessionLogger: SessionLogger

    private val ACTION_USB_PERMISSION = "com.signaldeck.scope.USB_PERMISSION"
    private val BAUD_RATE = 250000

    private val handler = Handler(Looper.getMainLooper())
    private var readThread: Thread? = null

    @Volatile private var keepReading = false

    private var dialFrequency = 1000.0
    private val F_MIN = 1.0
    private val F_MAX = 20000.0

    private var arrowSpeed = 1
    private var repeatDirection = 0
    private var sweepStartFreq = 0.0
    private var liveCapture = false

    private var capturing = false
    private var capRate = 0.0
    private var capSamples: IntArray? = null
    private var lastMeasuredHz = 0.0
    private var lastWaveform = false

    // --- visible-log filtering (on-screen log shows only changes + commands) ---
    private var lastLoggedTarget = -1.0
    private var lastErrWarnMs = 0L
    private var lastCaptureLabel: String? = null
    private var lastShownText = ""
    private var lastShownMs = 0L
    private var dialDragging = false

    // --- frequency send throttling ---
    private var lastSentFreq = 1000.0
    private var lastFreqTxMs = 0L

    // --- drawer / screens ---
    private var currentScreen = 0 // 0 = main app, 1 = logbook
    private var drawerOpen = false
    private var drawerAnimating = false
    private var logbookIsLive = true
    private var drawerDownX = 0f

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
            if (!capturing) {
                sessionLogger.log("TX", "C")
                serial?.writeLine("C")
            }
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
                        sessionLogger.log("SYS", "USB permission denied")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("USB device attached. Tap Connect.")
                    sessionLogger.log("SYS", "USB device attached")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLog("USB device detached.")
                    sessionLogger.log("SYS", "USB device detached")
                    disconnect("device detached")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        sessionLogger = SessionLogger(this)
        sessionLogger.onLogLine = { line -> handler.post { appendLogbookLine(line) } }

        scopeView = findViewById(R.id.scopeView)
        dialView = findViewById(R.id.dialView)

        slideRoot = findViewById(R.id.slideRoot)
        mainPage = findViewById(R.id.mainPage)
        logbookPage = findViewById(R.id.logbookPage)
        scrimView = findViewById(R.id.scrimView)
        drawerView = findViewById(R.id.drawerView)
        menuBtnMain = findViewById(R.id.menuBtnMain)
        menuBtnLog = findViewById(R.id.menuBtnLog)
        drawerMainOpt = findViewById(R.id.drawerMainOpt)
        drawerLogOpt = findViewById(R.id.drawerLogOpt)

        liveBtn = findViewById(R.id.liveBtn)
        refreshFilesBtn = findViewById(R.id.refreshFilesBtn)
        viewerTitle = findViewById(R.id.viewerTitle)
        logbookView = findViewById(R.id.logbookView)
        logbookScroll = findViewById(R.id.logbookScroll)
        sessionsList = findViewById(R.id.sessionsList)

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
        setupDrawer()

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
            if (serial != null) disconnect("user requested") else requestDevice()
        }

        clearLogBtn.setOnClickListener {
            logView.text = ""
            sessionLogger.log("APP", "on-screen log cleared by user")
        }

        sendBtn.setOnClickListener {
            val command = cmdInput.text.toString().trim()
            if (command.isNotEmpty()) {
                appendCommandLog(command)
                sessionLogger.log("TX", command)
                serial?.writeLine(command)
                cmdInput.setText("")
            }
        }

        liveCaptureBtn.setOnClickListener {
            liveCapture = !liveCapture
            if (liveCapture) {
                lastCaptureLabel = null
                liveCaptureBtn.text = "Stop Live Capture"
                appendLog("Live capture started.")
                sessionLogger.log("APP", "live capture ON")
                handler.post(liveCaptureLoop)
            } else {
                liveCaptureBtn.text = "Start Live Capture"
                appendLog("Live capture stopped.")
                sessionLogger.log("APP", "live capture OFF")
            }
        }

        reconBtn.setOnClickListener {
            liveCapture = false
            liveCaptureBtn.text = "Start Live Capture"
            scopeView.showReconstructed()
            rVoltage.text = "Voltage: --"
            appendLog("Showing reconstructed waveform.")
            sessionLogger.log("APP", "showing reconstructed waveform")
        }

        zoomInBtn.setOnClickListener { scopeView.zoomIn(); sessionLogger.log("APP", "zoom in") }
        zoomOutBtn.setOnClickListener { scopeView.zoomOut(); sessionLogger.log("APP", "zoom out") }
        resetZoomBtn.setOnClickListener { scopeView.resetZoom(); sessionLogger.log("APP", "zoom reset") }

        coarseModeBtn.setOnClickListener {
            dialView.scaleMode = "linear"
            dialView.setFrequency(dialFrequency)
            appendLog("Dial: LINEAR — 270° sweep = 1 Hz → 20 kHz")
            sessionLogger.log("APP", "dial scale → linear")
        }

        fineModeBtn.setOnClickListener {
            dialView.scaleMode = "log"
            dialView.setFrequency(dialFrequency)
            appendLog("Dial: LOG — 270° sweep = 1 Hz → 20 kHz, fine at low Hz")
            sessionLogger.log("APP", "dial scale → log")
        }

        speed1xBtn.setOnClickListener { arrowSpeed = 1; appendLog("Arrow speed: 1×"); sessionLogger.log("APP", "arrow speed 1x") }
        speed2xBtn.setOnClickListener { arrowSpeed = 2; appendLog("Arrow speed: 2×"); sessionLogger.log("APP", "arrow speed 2x") }

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
                    sweepStartFreq = dialFrequency
                    changeFrequencyFromArrow()
                    handler.removeCallbacks(repeatRunnable)
                    handler.postDelayed(repeatRunnable, 250L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatDirection = 0
                    handler.removeCallbacks(repeatRunnable)
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        sessionLogger.log(
                            "APP",
                            "sweep ${if (direction < 0) "◀" else "▶"} ${arrowSpeed}×: " +
                                String.format(Locale.US, "%.2f → %.2f Hz", sweepStartFreq, dialFrequency)
                        )
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun setupDial() {
        dialView.onFrequency = { f ->
            dialDragging = true
            dialFrequency = f
            updateDialDisplay()
            throttledFrequencySend()
        }
        dialView.onCommit = { f ->
            dialDragging = false
            dialFrequency = f
            updateDialDisplay()
            // only send (and log) if the value actually moved
            if (Math.abs(f - lastSentFreq) > 0.0049) {
                sendFrequency()
                sessionLogger.log("APP", "dial → ${String.format(Locale.US, "%.2f Hz", f)} (scale ${dialView.scaleMode})")
            }
        }
    }

    // ================= DRAWER (menu window) =================

    private fun setupDrawer() {
        // swipe from the left edge opens the menu window
        slideRoot.onLeftEdgeSwipe = { openDrawer() }
        menuBtnMain.setOnClickListener { openDrawer() }
        menuBtnLog.setOnClickListener { openDrawer() }

        // tap the dark area behind the drawer to close it
        scrimView.setOnClickListener { closeDrawer() }

        // tap an option → go there
        drawerMainOpt.setOnClickListener { goMain() }
        drawerLogOpt.setOnClickListener { goLogbook() }

        // swipe the drawer right to dismiss it
        drawerView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> drawerDownX = e.x
                MotionEvent.ACTION_UP -> if (e.x - drawerDownX > 120f) closeDrawer()
            }
            false
        }
    }

    private fun drawerWidth(): Float =
        if (drawerView.width > 0) drawerView.width.toFloat()
        else (240f * resources.displayMetrics.density)

    private fun openDrawer() {
        if (drawerOpen || drawerAnimating) return
        drawerAnimating = true
        updateDrawerHighlight()
        scrimView.visibility = View.VISIBLE
        drawerView.visibility = View.VISIBLE
        drawerView.translationX = -drawerWidth()
        drawerView.animate().translationX(0f).setDuration(200).withEndAction {
            drawerAnimating = false
            drawerOpen = true
        }.start()
    }

    private fun closeDrawer() {
        if (!drawerOpen || drawerAnimating) return
        drawerAnimating = true
        scrimView.visibility = View.GONE
        drawerView.animate().translationX(-drawerWidth()).setDuration(200).withEndAction {
            drawerView.visibility = View.GONE
            drawerAnimating = false
            drawerOpen = false
        }.start()
    }

    private fun goMain() {
        if (currentScreen != 0) {
            currentScreen = 0
            logbookPage.visibility = View.GONE
            mainPage.visibility = View.VISIBLE
        }
        closeDrawer()
    }

    private fun goLogbook() {
        if (currentScreen != 1) {
            currentScreen = 1
            mainPage.visibility = View.GONE
            logbookPage.visibility = View.VISIBLE
            refreshSessionList()
        }
        closeDrawer()
    }

    private fun updateDrawerHighlight() {
        val activeBg = 0xFF123524.toInt()
        val idleBg = 0x00000000
        val activeColor = 0xFF4DFFA0.toInt()
        val idleColor = 0xFFBFE8CD.toInt()
        val mainTitle = drawerMainOpt.getChildAt(0) as TextView
        val logTitle = drawerLogOpt.getChildAt(0) as TextView
        drawerMainOpt.setBackgroundColor(if (currentScreen == 0) activeBg else idleBg)
        mainTitle.setTextColor(if (currentScreen == 0) activeColor else idleColor)
        drawerLogOpt.setBackgroundColor(if (currentScreen == 1) activeBg else idleBg)
        logTitle.setTextColor(if (currentScreen == 1) activeColor else idleColor)
    }

    // ================= LOGBOOK =================

    private fun appendLogbookLine(line: String) {
        if (!logbookIsLive) return
        logbookView.append(line + "\n")
        val s = logbookView.text.toString()
        if (s.length > 200000) {
            logbookView.setText(s.substring(s.length - 100000))
        }
        val child = logbookScroll.getChildAt(0) ?: return
        val diff = child.bottom - (logbookScroll.height + logbookScroll.scrollY)
        if (diff <= 200) logbookScroll.post { logbookScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshSessionList() {
        sessionsList.removeAllViews()
        val files = sessionLogger.listSessions()
        if (files.isEmpty()) {
            val t = makeRow("No saved sessions yet.")
            t.setTextColor(0xFF6F9A80.toInt())
            sessionsList.addView(t)
            return
        }
        val dateFmt = SimpleDateFormat("dd MMM yyyy · HH:mm:ss", Locale.US)
        for (f in files) {
            val t = makeRow("${f.name}\n${dateFmt.format(Date(f.lastModified()))} · ${f.length() / 1024} KB")
            t.setTextColor(0xFFBFE8CD.toInt())
            t.isClickable = true
            t.setOnClickListener { openSessionFile(f) }
            sessionsList.addView(t)
        }
    }

    private fun makeRow(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 10.5f
        t.typeface = Typeface.MONOSPACE
        t.setPadding(16, 12, 16, 12)
        return t
    }

    private fun openSessionFile(f: File) {
        logbookIsLive = false
        viewerTitle.text = f.name
        logbookView.text = "Loading…"
        Thread {
            val text = try { f.readText() } catch (e: Exception) { "Failed to read: ${e.message}" }
            val shown = if (text.length > 300000)
                text.substring(0, 300000) + "\n\n…(truncated — ${text.length} chars total)"
            else text
            handler.post {
                logbookView.text = shown
                logbookScroll.scrollTo(0, 0)
            }
        }.start()
    }

    // ================= FREQUENCY =================

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
        sessionLogger.log("APP", "nudge ${if (amount > 0) "+" else ""}${amount} → ${String.format(Locale.US, "%.2f Hz", dialFrequency)}")
    }

    private fun updateDialDisplay() {
        val value = String.format(Locale.US, "%.2f Hz", dialFrequency)
        rTargetBig.text = value
        rTarget.text = "Target: $value"
        dialView.setFrequency(dialFrequency)
    }

    private fun throttledFrequencySend() {
        // while dragging, the dial fires many times a second —
        // cap serial spam at 10 commands/sec; the final value is always
        // sent on finger-up via onCommit.
        val now = SystemClock.elapsedRealtime()
        if (now - lastFreqTxMs >= 100L) sendFrequency()
    }

    private fun sendFrequency() {
        val command = String.format(Locale.US, "F%.2f", dialFrequency)
        lastFreqTxMs = SystemClock.elapsedRealtime()
        lastSentFreq = dialFrequency
        sessionLogger.log("TX", command)
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
        sessionLogger.log("SYS", "requesting USB permission for ${deviceDesc(device)}")
    }

    private fun connectToDevice(device: UsbDevice) {
        try {
            val manager = UsbSerialManager(usbManager, device)
            val opened = manager.open(BAUD_RATE)
            if (!opened) {
                appendLog("Could not open USB serial port.")
                sessionLogger.log("SYS", "open failed for ${deviceDesc(device)}")
                manager.close()
                return
            }
            serial = manager
            connStatus.text = "Connected"
            connectBtn.text = "Disconnect"
            appendLog("USB serial connected.")
            sessionLogger.startSession("${deviceDesc(device)} @ $BAUD_RATE baud")
            appendLog(manager.debugInInfo())
            startReading()
            sendFrequency()
        } catch (e: Exception) {
            serial = null
            connStatus.text = "Disconnected"
            connectBtn.text = "Connect"
            appendLog("Connection failed: ${e.message}")
            sessionLogger.log("SYS", "exception: ${e.message}")
        }
    }

    private fun deviceDesc(d: UsbDevice): String =
        String.format(Locale.US, "USB %04X:%04X %s", d.vendorId, d.productId, d.deviceName)

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
                        sessionLogger.log("SYS", "read error: ${e.message}")
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

        // Everything from the Uno goes into the logbook file.
        // Long sample lines are truncated in the file to keep it readable.
        if (capturing && text.matches(Regex("^[0-9,]+$")) && text.length > 120) {
            sessionLogger.log("RX", text.substring(0, 120) + " …(sample data, ${text.length} chars total)")
        } else {
            sessionLogger.log("RX", text)
        }

        if (text.startsWith("AI>")) {
            // Device feedback: shown, but suppressed while dragging the dial
            // (it would still spam; the logbook keeps every one).
            if (!dialDragging) showOnce(text, Color.CYAN)
            return
        }

        if (text.startsWith("CAP,")) {
            val parts = text.split(",")
            capturing = true
            capRate = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            capSamples = null
            return
        }
        if (capturing) {
            if (text == "ENDCAP") {
                capturing = false
                onCaptureComplete()
                return
            }
            // Sample lines may arrive split across several lines — append, don't overwrite.
            if (text.matches(Regex("^[0-9,]+$"))) {
                val arr = text.split(",").mapNotNull { it.toIntOrNull() }.toIntArray()
                val prev = capSamples
                capSamples = if (prev == null) arr else prev.plus(arr)
                return
            }
        }

        if (text.startsWith("Target:")) {
            val targetHz = Regex("Target:\\s*([\\d.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            val measuredHz = Regex("Measured:\\s*([\\d.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            val dutyStr = Regex("Duty:\\s*([\\d.]+)").find(text)?.groupValues?.get(1)
            val errStr = Regex("Err:\\s*([\\d.]+)").find(text)?.groupValues?.get(1)
            val noSignal = text.contains("NO SIGNAL")

            // Readouts always update silently (no visible-log spam).
            targetHz?.let { rTarget.text = String.format(Locale.US, "Target: %.2f Hz", it) }
            dutyStr?.let {
                rDuty.text = "Duty: $it%"
                scopeView.liveDuty = it.toDoubleOrNull() ?: 50.0
            }
            lastWaveform = !noSignal
            if (lastWaveform) measuredHz?.let { lastMeasuredHz = it }
            rMeasured.text = if (lastWaveform && measuredHz != null)
                String.format(Locale.US, "Measured: %.2f Hz", measuredHz)
            else "Measured: NO SIGNAL"
            rRate.text = if (lastWaveform) "Signal present" else "No signal"
            scopeView.liveFreq = if (lastWaveform && measuredHz != null) measuredHz else dialFrequency
            scopeView.waveformPresent = lastWaveform
            if (!liveCapture && scopeView.mode != "captured") scopeView.showReconstructed()

            // Visible log: only when the frequency actually changes.
            if (targetHz != null && targetHz != lastLoggedTarget) {
                val prev = lastLoggedTarget
                lastLoggedTarget = targetHz
                val shown = if (prev < 0)
                    "Target: ${String.format(Locale.US, "%.2f Hz", targetHz)}"
                else
                    "Target: ${String.format(Locale.US, "%.2f", prev)} → ${String.format(Locale.US, "%.2f Hz", targetHz)}"
                appendStyledLog(shown, Color.GREEN)
            }

            // One warning when the error gets large — not one per line.
            val err = errStr?.toDoubleOrNull() ?: 0.0
            val now = SystemClock.elapsedRealtime()
            if (err > 2.0 && now - lastErrWarnMs > 5000) {
                lastErrWarnMs = now
                appendStyledLog("⚠ Err ${errStr}% — measured disagrees with target", 0xFFFF8A65.toInt())
            }
            return
        }

        // Anything else: logbook only, never the visible log.
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

        sessionLogger.log("APP", "capture done: ${samples.size} samples @ ${String.format(Locale.US, "%.0f", capRate)} Hz → $label")
        if (!liveCapture || label != lastCaptureLabel) {
            lastCaptureLabel = label
            appendStyledLog("Capture: ${samples.size} samples — $label", 0xFF81C784.toInt())
        }

        if (spc >= 4) {
            scopeView.showCaptured(samples, label, capRate)
        } else {
            scopeView.showReconstructed()
        }
    }

    private fun disconnect(reason: String) {
        val wasConnected = serial != null
        keepReading = false
        repeatDirection = 0
        liveCapture = false
        if (wasConnected) liveCaptureBtn.text = "Start Live Capture"
        handler.removeCallbacks(repeatRunnable)
        try { readThread?.interrupt() } catch (_: Exception) {}
        readThread = null
        try { serial?.close() } catch (_: Exception) {}
        serial = null

        if (wasConnected) {
            connStatus.text = "Disconnected"
            connectBtn.text = "Connect"
            rMeasured.text = "Measured: --"
            rRate.text = "Rate: --"
            rVoltage.text = "Voltage: -- (no real capture yet)"
            scopeView.waveformPresent = false
            lastWaveform = false
            lastLoggedTarget = -1.0
            sessionLogger.endSession(reason)
            refreshSessionList()
            appendLog("Disconnected.")
        }
    }

    // ================= LOGGING HELPERS =================

    private fun appendLog(message: String) {
        handler.post {
            val current = logView.text.toString()
            logView.text = if (current.isEmpty()) message else "$current\n$message"
        }
    }

    private fun appendCommandLog(command: String) {
        appendStyledLog(">> $command", Color.RED)
    }

    /** AI> dedupe: identical device feedback within 2 s is shown once. */
    private fun showOnce(text: String, color: Int, gapMs: Long = 2000L) {
        val now = SystemClock.elapsedRealtime()
        if (text == lastShownText && now - lastShownMs < gapMs) return
        lastShownText = text
        lastShownMs = now
        appendStyledLog(text, color)
    }

    private fun appendStyledLog(message: String, color: Int) {
        val text = SpannableString("$message\n")
        text.setSpan(ForegroundColorSpan(color), 0, message.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        logView.append(text)
    }

    override fun onPause() {
        scopeView.paused = true
        super.onPause()
    }

    override fun onResume() {
        scopeView.paused = false
        super.onResume()
    }

    override fun onDestroy() {
        repeatDirection = 0
        handler.removeCallbacks(repeatRunnable)
        keepReading = false
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        disconnect("app closed")
        super.onDestroy()
    }
}
