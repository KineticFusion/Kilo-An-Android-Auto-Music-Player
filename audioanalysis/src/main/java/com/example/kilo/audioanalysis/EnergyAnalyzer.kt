package com.example.kilo.audioanalysis

object EnergyAnalyzer {
    fun score(analysis: TrackAnalysisEntity?): Double? = analysis?.energy?.coerceIn(0.0, 100.0)
}
