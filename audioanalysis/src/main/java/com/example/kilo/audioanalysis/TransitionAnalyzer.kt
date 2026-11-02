package com.example.kilo.audioanalysis

data class AudioTransitionAnalysis(
    val energyDifference: Double?,
    val beatCompatibility: Double?,
    val incomingIntroIsSoft: Boolean,
    val outgoingOutroIsSoft: Boolean,
    val incomingNearSilent: Boolean
)

object TransitionAnalyzer {
    fun analyze(current: TrackAnalysisEntity?, next: TrackAnalysisEntity?): AudioTransitionAnalysis =
        AudioTransitionAnalysis(
            energyDifference = if (current != null && next != null) kotlin.math.abs(current.energy - next.energy) else null,
            beatCompatibility = BeatAnalyzer.compatibility(current, next),
            incomingIntroIsSoft = IntroDetector.hasSoftIntro(next),
            outgoingOutroIsSoft = OutroDetector.suggestsEarlyFade(current),
            incomingNearSilent = SilenceDetector.isNearSilent(next)
        )
}
