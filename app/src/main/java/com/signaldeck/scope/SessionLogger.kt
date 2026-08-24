package com.signaldeck.scope

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Writes one .txt logbook file per connection session.
 * A unique file is created on every connect and sealed on every disconnect.
 * Every line is timestamped to the millisecond, e.g.:
 *
 *   [2026-08-24 12:42:26.123] [APP] dial → 8.80 Hz
 *   [2026-08-24 12:42:26.234] [TX] F8.80
 *   [2026-08-24 12:42:26.456] [RX] Target: 8.800 Hz | Measured: 8.800 Hz | ...
 *
 * Kinds: APP = user action, TX = sent to Uno, RX = received from Uno, SYS = system event.
 */
class SessionLogger(context: Context) {

    /** Fired (on the IO thread) after each line is written — feeds the live logbook view. */
    var onLogLine: ((String) -> Unit)? = null

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val dir = File(
        context.getExternalFilesDir(null) ?: context.filesDir,
        "Logbook"
    ).apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var writer: BufferedWriter? = null
    private var sessionStartMs = 0L
    private var currentFileRef: File? = null

    val currentFile: File? get() = currentFileRef

    fun startSession(deviceDesc: String): File {
        endSession("restart")
        var f = File(dir, "SignalDeck_${fileFmt.format(Date())}.txt")
        var n = 2
        while (f.exists()) {
            f = File(dir, "SignalDeck_${fileFmt.format(Date())}_$n.txt")
            n++
        }
        currentFileRef = f
        sessionStartMs = System.currentTimeMillis()
        val w = BufferedWriter(FileWriter(f))
        w.writeLine("=== SignalDeck session logbook ===")
        w.writeLine("Started: ${timeFmt.format(Date())}")
        w.writeLine("Device: $deviceDesc")
        w.writeLine("Baud: 250000 8N1")
        w.writeLine("---")
        writer = w
        return f
    }

    fun log(kind: String, text: String) {
        val w = writer ?: return
        val line = "[${timeFmt.format(Date())}] [$kind] $text"
        io.execute {
            try {
                w.write(line)
                w.newLine()
                w.flush()
            } catch (_: Exception) {
            }
            onLogLine?.invoke(line)
        }
    }

    fun endSession(reason: String) {
        val w = writer ?: return
        writer = null
        val start = sessionStartMs
        io.execute {
            try {
                val dur = if (start > 0) System.currentTimeMillis() - start else 0
                w.write("---")
                w.newLine()
                w.writeLine("Ended: ${timeFmt.format(Date())} ($reason)")
                w.writeLine(String.format(Locale.US, "Duration: %.1f s", dur / 1000.0))
                w.writeLine("=== end of session ===")
            } catch (_: Exception) {
            } finally {
                try {
                    w.close()
                } catch (_: Exception) {
                }
                currentFileRef = null
            }
        }
    }

    fun listSessions(): List<File> =
        dir.listFiles { f -> f.name.startsWith("SignalDeck_") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    private fun BufferedWriter.writeLine(s: String) {
        write(s)
        newLine()
    }
}
