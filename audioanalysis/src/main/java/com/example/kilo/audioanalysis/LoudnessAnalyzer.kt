package com.example.kilo.audioanalysis

import kotlin.math.pow

object LoudnessAnalyzer {
    /** Approximate linear RMS amplitude, clamped to keep gain compensation bounded. */
    fun linearRms(analysis: TrackAnalysisEntity?): Double? = analysis?.loudnessDb?.let { db ->
        10.0.pow((db.coerceIn(-80.0, 0.0)) / 20.0).coerceIn(0.0001, 1.0)
    }
}
