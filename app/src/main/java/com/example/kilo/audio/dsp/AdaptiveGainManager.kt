package com.example.kilo.audio.dsp

import com.example.kilo.audio.engine.AudioOutputRoute
import com.example.kilo.audio.profiles.SoundProfile
import com.example.kilo.audioanalysis.TrackAnalysisEntity

/** Applies only bounded, per-track compensation; it does not ride gain while a track is playing. */
class AdaptiveGainManager(
    private val replayGain: ReplayGainEngine = ReplayGainEngine(),
    private val outputOptimizer: OutputOptimizationManager = OutputOptimizationManager()
) {
    fun inputGainDb(
        profile: SoundProfile,
        analysis: TrackAnalysisEntity?,
        route: AudioOutputRoute,
        mode: LoudnessMode,
        safeGainStageAvailable: Boolean
    ): Float {
        if (!safeGainStageAvailable || !profile.usesLimiter) return 0f
        val ceiling = outputOptimizer.gainCeilingDb(route).coerceAtMost(MAX_GAIN_DB)
        val correction = replayGain.trackGainDb(analysis, limitDb = mode.correctionLimitDb)
        return (profile.inputGainDb + correction).coerceIn(MIN_GAIN_DB, ceiling)
    }

    private companion object {
        const val MIN_GAIN_DB = -4f
        const val MAX_GAIN_DB = 5.5f
    }
}
