package com.example.kilo.audioanalysis

object SilenceDetector {
    /** Conservatively identifies near-silent analyzed tracks; it is not a live silence scan. */
    fun isNearSilent(analysis: TrackAnalysisEntity?): Boolean =
        analysis?.let { it.loudnessDb <= -55.0 } ?: false
}
