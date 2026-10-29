package com.example.kilo.audio.dsp

import com.example.kilo.audio.engine.AudioOutputRoute
import com.example.kilo.audio.profiles.SoundProfile

data class AudioProfilePlan(
    val requestedProfileId: String,
    val activeProfile: SoundProfile,
    val output: AudioOutputRoute,
    val eqGainsDb: List<Float>,
    val inputGainDb: Float,
    val virtualizerStrength: Int,
    val limiterEnabled: Boolean,
    val transparentMode: Boolean,
    val analysisUsed: Boolean,
    val description: String
)
