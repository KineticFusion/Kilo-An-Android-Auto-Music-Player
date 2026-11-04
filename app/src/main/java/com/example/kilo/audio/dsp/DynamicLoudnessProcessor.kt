package com.example.kilo.audio.dsp

import com.example.kilo.audio.profiles.SoundProfile
import com.example.kilo.audioanalysis.TrackAnalysisEntity

/** Conservative gain staging. Positive gain is only used with the session limiter. */
class DynamicLoudnessProcessor {
    fun inputGain(profile: SoundProfile, analysis: TrackAnalysisEntity?, limiterAvailable: Boolean): Float {
        if (!limiterAvailable || !profile.usesLimiter) return 0f
        // Cached PCM RMS is an approximation rather than Spotify-style integrated LUFS.
        // Use it conservatively when available to reduce large level jumps; otherwise keep
        // the same protected baseline gain for every track.
        val trackCorrectionDb = analysis?.loudnessDb?.let { measuredDb ->
            (TARGET_RMS_DB - measuredDb).toFloat().coerceIn(-4f, 4f)
        } ?: 0f
        return (profile.inputGainDb + trackCorrectionDb).coerceIn(-4f, 5.5f)
    }

    private companion object {
        const val TARGET_RMS_DB = -17.5
    }
}
