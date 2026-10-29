package com.example.kilo.audio.engine

import com.example.kilo.audio.dsp.AudioProfilePlan
import com.example.kilo.audio.dsp.CarAudioProcessor
import com.example.kilo.audio.profiles.SoundProfileRepository
import com.example.kilo.audioanalysis.TrackAnalysisEntity

/** Converts route, selected profile, and cached track analysis into a bounded session-effect plan. */
class AudioPipeline(private val profiles: SoundProfileRepository) {
    private val carProcessor = CarAudioProcessor()

    fun createPlan(route: AudioOutputRoute, analysis: TrackAnalysisEntity?, limiterAvailable: Boolean): AudioProfilePlan {
        val selectedId = profiles.selectedId()
        val profile = profiles.forOutput(route)
        if (profile.id == SoundProfileRepository.ORIGINAL) {
            val inputGain = com.example.kilo.audio.dsp.DynamicLoudnessProcessor()
                .inputGain(profile, null, limiterAvailable)
            return AudioProfilePlan(
                selectedId, profile, route, List(10) { 0f }, inputGain, 0,
                limiterEnabled = limiterAvailable, transparentMode = false,
                analysisUsed = false,
                description = if (limiterAvailable) "Original tone with ${"%.1f".format(inputGain)} dB level boost and peak limiting" else "Original tone; this Android version cannot safely apply the level boost"
            )
        }
        return carProcessor.plan(selectedId, profile, route, analysis, limiterAvailable)
    }
}
