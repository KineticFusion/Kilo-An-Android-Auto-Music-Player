package com.example.kilo.audio.dsp

/** Safe settings consumed by the API-28+ DynamicsProcessing adapter. */
data class LimiterConfiguration(
    val enabled: Boolean = true,
    val attackMs: Float = 2.0f,
    val releaseMs: Float = 100.0f,
    val ratio: Float = 20.0f,
    val thresholdDb: Float = -1.0f,
    val postGainDb: Float = 0f
)

class SmartLimiter {
    fun configuration(enabled: Boolean) = LimiterConfiguration(enabled = enabled)
}
