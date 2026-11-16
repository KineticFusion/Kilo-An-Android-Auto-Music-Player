package com.example.kilo.audio.dsp

/** Safe settings consumed by the API-28+ DynamicsProcessing adapter. */
data class LimiterConfiguration(
    val enabled: Boolean = true,
    val attackMs: Float = 10.0f,    // Slower attack preserves transients
    val releaseMs: Float = 150.0f,  // Slower release prevents pumping
    val ratio: Float = 6.0f,        // Gentler ratio (6:1 instead of 20:1) for more natural sound
    val thresholdDb: Float = -6.0f, // Higher threshold (-6dB instead of -1dB) catches only peaks
    val postGainDb: Float = 0f
)

class SmartLimiter {
    fun configuration(enabled: Boolean) = LimiterConfiguration(enabled = enabled)
}