package com.example.kilo.audio.dsp

import com.example.kilo.audio.engine.AudioOutputKind
import com.example.kilo.audio.engine.AudioOutputRoute
import com.example.kilo.audio.profiles.SoundProfile
import com.example.kilo.audioanalysis.TrackAnalysisEntity

class CarAudioProcessor(
    private val dynamicEq: DynamicEq = DynamicEq(),
    private val bass: DynamicBassProcessor = DynamicBassProcessor(),
    private val loudness: DynamicLoudnessProcessor = DynamicLoudnessProcessor()
) {
    fun plan(requestedId: String, profile: SoundProfile, output: AudioOutputRoute, analysis: TrackAnalysisEntity?, limiterAvailable: Boolean): AudioProfilePlan {
        val gains = dynamicEq.gains(profile, analysis).zip(bass.gains(profile, analysis)) { a, b -> ((a + b) / 2f).coerceIn(-3f, 2.7f) }
        val carRoute = output.kind == AudioOutputKind.CAR_BLUETOOTH || output.kind == AudioOutputKind.ANDROID_AUTO
        val inputGain = loudness.inputGain(profile, analysis, limiterAvailable)
        return AudioProfilePlan(
            requestedId, profile, output, gains, inputGain,
            profile.virtualizerStrength.coerceIn(0, if (carRoute) 150 else 100),
            profile.usesLimiter && limiterAvailable,
            profile.id == "original" || profile.id == "auto",
            analysis != null,
            when {
                profile.id == "original" || profile.id == "auto" -> "Original tone; no sound coloration"
                inputGain > 0 -> "${"%.1f".format(inputGain)} dB gain with peak limiting"
                else -> profile.description
            }
        )
    }
}

val EQ_FREQUENCIES_HZ = listOf(31f, 63f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

internal fun List<Float>.padTo(count: Int): List<Float> = take(count) + List((count - size).coerceAtLeast(0)) { 0f }
