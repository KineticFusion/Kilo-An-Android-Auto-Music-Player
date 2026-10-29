package com.example.kilo.audio.profiles

/** A conservative sound recipe. Gains are deliberately capped to preserve headroom. */
data class SoundProfile(
    val id: String,
    val title: String,
    val description: String,
    val gainsDb: List<Float>,
    val inputGainDb: Float = 0f,
    val virtualizerStrength: Int = 0,
    val usesLimiter: Boolean = true
)
