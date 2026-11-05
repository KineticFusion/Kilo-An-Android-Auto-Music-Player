package com.example.kilo.audio.dsp

import com.example.kilo.audio.profiles.SoundProfile
import com.example.kilo.audioanalysis.TrackAnalysisEntity

/** Conservative gain staging. Positive gain is only used with the session limiter. */
class DynamicLoudnessProcessor {
    private val adaptiveGain = AdaptiveGainManager()

    fun inputGain(
        profile: SoundProfile,
        analysis: TrackAnalysisEntity?,
        safeGainStageAvailable: Boolean,
        mode: LoudnessMode = LoudnessMode.NORMAL,
        route: com.example.kilo.audio.engine.AudioOutputRoute = com.example.kilo.audio.engine.AudioOutputRoute()
    ): Float = adaptiveGain.inputGainDb(profile, analysis, route, mode, safeGainStageAvailable)
}
