package com.example.kilo.audioanalysis

import kotlin.math.abs

object BeatAnalyzer {
    fun compatibility(current: TrackAnalysisEntity?, next: TrackAnalysisEntity?): Double? {
        val a = current?.bpm ?: return null
        val b = next?.bpm ?: return null
        val normalizedB = when {
            b <= 0.0 -> return null
            b < 70.0 -> b * 2.0
            b > 150.0 -> b / 2.0
            else -> b
        }
        return (1.0 - abs(a - normalizedB) / maxOf(a, normalizedB, 1.0)).coerceIn(0.0, 1.0)
    }
}
