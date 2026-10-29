package com.example.kilo.audio.dsp

import com.example.kilo.audio.profiles.SoundProfile
import com.example.kilo.audioanalysis.TrackAnalysisEntity

/** Conservative gain staging. Positive gain is only used with the session limiter. */
class DynamicLoudnessProcessor {
    fun inputGain(profile: SoundProfile, analysis: TrackAnalysisEntity?, limiterAvailable: Boolean): Float {
        if (!limiterAvailable || !profile.usesLimiter) return 0f
        val trackCorrection = when {
            analysis == null -> 0f
            analysis.loudnessDb < -25.0 -> 0.75f
            analysis.loudnessDb < -19.0 -> 0.35f
            analysis.loudnessDb > -9.0 -> -0.5f
            else -> 0f
        }
        return (profile.inputGainDb + trackCorrection).coerceIn(0f, 5.5f)
    }
}
