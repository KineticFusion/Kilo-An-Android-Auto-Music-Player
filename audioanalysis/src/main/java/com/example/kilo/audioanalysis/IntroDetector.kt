package com.example.kilo.audioanalysis

object IntroDetector {
    /** Uses cached whole-track analysis only; returns null when there is no measured PCM window. */
    fun hasSoftIntro(analysis: TrackAnalysisEntity?): Boolean =
        analysis?.let { it.energy < 30.0 && it.loudnessDb < -24.0 } ?: false
}
