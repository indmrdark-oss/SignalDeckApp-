package com.signaldeck.scope

/**
 * Shared live state so Fullscreen/Pro Scope screens can display the
 * same real data as MainActivity without opening a second USB connection.
 * MainActivity owns the actual USB link; other screens just read this.
 */
object ScopeDataHub {
    @Volatile var capturedSamples: IntArray? = null
    @Volatile var capturedSampleRateHz: Double = 0.0
    @Volatile var liveFreq: Double = 0.0
    @Volatile var liveDuty: Double = 50.0
    @Volatile var waveformPresent: Boolean = false
    @Volatile var mode: String = "reconstructed"
    @Volatile var fidelityLabel: String = "RECONSTRUCTED"
    @Volatile var connected: Boolean = false
    @Volatile var liveCaptureActive: Boolean = false

    var sendCommand: ((String) -> Unit)? = null
    var toggleLiveCapture: (() -> Unit)? = null
}
