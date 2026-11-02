package com.example.kilo.audioanalysis

object OutroDetector {
    /** Metadata-level hint only. Accurate outro detection requires a decoded tail window. */
    fun suggestsEarlyFade(analysis: TrackAnalysisEntity?): Boolean =
        analysis?.let { it.energy < 25.0 && it.loudnessDb < -28.0 } ?: false
}
