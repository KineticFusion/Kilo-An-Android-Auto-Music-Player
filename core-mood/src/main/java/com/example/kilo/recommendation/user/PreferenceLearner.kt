package com.example.kilo.recommendation.user

import com.example.kilo.companion.ListeningEvent
import com.example.kilo.recommendation.genome.TrackGenome

object PreferenceLearner {
    fun updateGenome(
        genome: TrackGenome,
        events: List<ListeningEvent>,
        aliases: Set<String> = setOf(genome.trackId)
    ): TrackGenome {
        val relevant = events.filter { it.trackId in aliases || it.trackId == genome.trackId }.takeLast(30)
        if (relevant.isEmpty()) return genome
        var session = genome.factor("sessionCompatibility")
        var repeat = genome.factor("repeatPotential")
        var familiarity = genome.familiarity
        relevant.forEach { event ->
            when (event.action) {
                "skip" -> {
                    session -= when { event.listenedMs < 5_000L -> 12f; event.listenedMs < 30_000L -> 6f; else -> 2f }
                }
                "complete", "listen" -> if (event.durationMs > 0 && event.listenedMs >= event.durationMs * .8) {
                    session += 3f
                    familiarity += 3f
                }
                "replay" -> { session += 4f; repeat += 8f; familiarity += 8f }
                "like" -> { session += 6f; repeat += 5f; familiarity += 5f }
                "unlike" -> session -= 8f
            }
        }
        val confidenceBoost = (relevant.size * .08f).coerceAtMost(.6f)
        val learnedConfidence = genome.factorConfidence.toMutableMap().apply {
            listOf("sessionCompatibility", "repeatPotential", "familiarity").forEach { key ->
                put(key, maxOf(this[key] ?: 0f, confidenceBoost))
            }
        }
        return TrackGenome.normalized(genome.copy(factors = genome.factors + mapOf(
            "sessionCompatibility" to session.coerceIn(0f, 100f),
            "repeatPotential" to repeat.coerceIn(0f, 100f),
            "familiarity" to familiarity.coerceIn(0f, 100f)
        ), factorConfidence = learnedConfidence))
    }
}
