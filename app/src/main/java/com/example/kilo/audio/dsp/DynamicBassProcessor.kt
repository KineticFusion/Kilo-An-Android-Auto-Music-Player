package com.example.kilo.audio.dsp

import com.example.kilo.audio.profiles.SoundProfile
import com.example.kilo.audioanalysis.TrackAnalysisEntity

/** Applies a small energy-based correction only when cached analysis exists. */
class DynamicBassProcessor {
    fun gains(profile: SoundProfile, analysis: TrackAnalysisEntity?): List<Float> {
        val source = profile.gainsDb.take(EQ_FREQUENCIES_HZ.size).padTo(EQ_FREQUENCIES_HZ.size)
        if (profile.id == "original" || profile.id == "auto") return List(EQ_FREQUENCIES_HZ.size) { 0f }
        val energy = analysis?.energy ?: return source
        val offset = when {
            energy < 25.0 -> 0.35f
            energy > 85.0 -> -0.25f
            else -> 0f
        }
        return source.mapIndexed { index, gain -> if (index <= 2) (gain + offset).coerceIn(-3f, 2.7f) else gain }
    }
}
