package com.example.kilo.recommendation.diagnostics

import com.example.kilo.recommendation.genome.GenomeMetrics

object RecommendationDashboard {
    fun snapshot() = RecommendationProfiler.snapshot() + GenomeMetrics.snapshot()
}
