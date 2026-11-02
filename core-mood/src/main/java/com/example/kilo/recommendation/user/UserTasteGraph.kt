package com.example.kilo.recommendation.user

import com.example.kilo.recommendation.genome.SimilarityGraph
import com.example.kilo.recommendation.genome.TrackGenome

/** Builds listener affinity clusters from numeric similarity, not artist/title labels. */
class UserTasteGraph {
    private val graph = SimilarityGraph()

    fun cluster(seeds: Collection<TrackGenome>, pool: Collection<TrackGenome>, limit: Int = 24): List<TrackGenome> {
        if (seeds.isEmpty()) return emptyList()
        val seedIds = seeds.map { it.trackId }.toSet()
        val candidates = pool.filter { it.trackId !in seedIds }
        seeds.forEach { graph.index(it, candidates) }
        val ids = seeds.flatMap { graph.similar(it.trackId, 20) }.distinct().toSet()
        return candidates.asSequence().filter { it.trackId in ids }
            .map { genome -> genome to seeds.maxOf { SimilarityGraph.similarity(it, genome) } }
            .sortedByDescending { it.second }
            .take(limit.coerceIn(0, 200))
            .map { it.first }
            .toList()
    }
}
