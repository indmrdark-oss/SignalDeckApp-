package com.signaldeck.scope

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AssistantActivity : AppCompatActivity() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant)

        chatContainer = findViewById(R.id.chatContainer)
        chatScroll = findViewById(R.id.chatScroll)
        chatInput = findViewById(R.id.chatInput)
        val sendBtn: Button = findViewById(R.id.chatSendBtn)
        val backBtn: TextView = findViewById(R.id.backBtn)

        backBtn.setOnClickListener { finish() }

        addBubble(
            "Hey, I'm your project assistant. I know the wiring, the commands, and every " +
            "feature we've built - ask me anything about Signal Deck. (Heads up: I run fully " +
            "offline on this device, so I answer from what I know about this project rather " +
            "than being a general AI.)",
            fromUser = false
        )

        sendBtn.setOnClickListener { sendMessage() }
        chatInput.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
    }

    private fun sendMessage() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) return
        addBubble(text, fromUser = true)
        chatInput.setText("")
        val reply = generateReply(text.lowercase())
        addBubble(reply, fromUser = false)
    }

    private fun addBubble(text: String, fromUser: Boolean) {
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(if (fromUser) Color.parseColor("#0A140D") else Color.parseColor("#BFE8CD"))
            textSize = 14f
            setPadding(28, 20, 28, 20)
            val bg = GradientDrawable().apply {
                cornerRadius = 28f
                setColor(
                    if (fromUser) Color.parseColor("#4DFFA0") else Color.parseColor("#0D150E")
                )
            }
            background = bg
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20 }
            gravity = if (fromUser) Gravity.END else Gravity.START
        }
        val bubbleParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        bubbleParams.width = (resources.displayMetrics.widthPixels * 0.78).toInt()
        row.addView(bubble, bubbleParams)
        chatContainer.addView(row)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Offline knowledge base about this specific project. */
    private fun generateReply(q: String): String {
        return when {
            "wire" in q || "wiring" in q || "connect" in q && "how" in q ->
                "Wiring: pot wiper→A0, pot outer legs→5V & GND. D9→MOSFET gate only, nothing else. " +
                "D10→a shared row, then two wires from that row to D2 (frequency counting) and A2 " +
                "(scope capture). USB→your tablet at 250000 baud."

            "duty" in q ->
                "To set duty cycle: send 'D' as its own message, wait for the prompt, then send the " +
                "percent (e.g. '30') as a separate message. Range is 1-99%."

            "lock" in q ->
                "Send 'L' to lock the current frequency (pot stops working). Send 'O' to unlock."

            "mode" in q || ("frequency" in q && "type" in q) ->
                "Send 'M', wait for the prompt, then type an exact frequency in Hz and it locks there."

            "capture" in q || "snapshot" in q ->
                "Use 'Start Live Capture' to pull real ADC frames continuously, or the graph screens' " +
                "capture. Above roughly 4-8kHz the ADC can't sample fast enough for a clean trace, so " +
                "it falls back to Reconstructed mode automatically and tells you when it does."

            "zoom" in q ->
                "Pinch two fingers on the graph to zoom (only works in Captured mode, not Reconstructed " +
                "- zooming a synthetic wave wouldn't mean anything real). Drag to pan once zoomed."

            "ch340" in q || "chip" in q ->
                "Your board uses a CH340G USB-to-serial chip, common on non-official Uno clones. The app " +
                "detects it automatically and uses vendor-specific baud configuration via the " +
                "usb-serial-for-android library."

            "voltage" in q ->
                "Voltage readouts (min/max/avg/Vpp) only ever come from real ADC bytes during a capture - " +
                "they're computed fresh every frame, never estimated or invented."

            "range" in q || "20khz" in q || "1hz" in q ->
                "Frequency range is 1Hz to 20kHz, mapped on a log scale across the pot so you get fine " +
                "control at low frequencies and still reach 20kHz at the top."

            "hi" in q || "hello" in q || "hey" in q ->
                "Hey! Ask me about wiring, commands, duty cycle, locking, capture modes, or zoom - " +
                "I know this project inside out."

            "thank" in q ->
                "Anytime."

            else ->
                "I don't have a specific answer for that yet - try asking about wiring, duty cycle, " +
                "locking, mode entry, capture/snapshot, zoom, or the CH340 chip."
        }
    }
}
