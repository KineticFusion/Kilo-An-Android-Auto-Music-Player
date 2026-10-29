package com.example.kilo.audio.dsp

import com.example.kilo.audio.profiles.SoundProfile
import com.example.kilo.audioanalysis.TrackAnalysisEntity

class DynamicEq {
    fun gains(profile: SoundProfile, analysis: TrackAnalysisEntity?): List<Float> {
        val base = profile.gainsDb.take(EQ_FREQUENCIES_HZ.size).padTo(EQ_FREQUENCIES_HZ.size)
        if (profile.id == "original" || profile.id == "auto") return base
        val presence = when (analysis?.mood) {
            "Calm", "Mellow" -> 0.15f
            "High Energy", "Upbeat" -> -0.1f
            else -> 0f
        }
        return base.mapIndexed { index, gain ->
            (if (index in 5..7) gain + presence else gain).coerceIn(-3f, 2.7f)
        }
    }
}
