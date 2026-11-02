package com.example.kilo.recommendation.diagnostics

import android.util.Log
import com.example.kilo.recommendation.ranking.RankedGenome

object RecommendationDebugger {
    fun why(item: RankedGenome): Map<String, Float> = mapOf(
        "mood" to item.breakdown.mood,
        "factors" to item.breakdown.factor,
        "session" to item.breakdown.session,
        "affinity" to item.breakdown.affinity,
        "discovery" to item.breakdown.discovery,
        "diversity" to item.breakdown.diversity,
        "context" to item.breakdown.context,
        "total" to item.breakdown.total
    )

    fun log(item: RankedGenome) = Log.d("GenomeX", "selected=${item.genome.trackId} score=${why(item)}")
}
