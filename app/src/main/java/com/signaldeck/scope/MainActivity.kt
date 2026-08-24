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
     
