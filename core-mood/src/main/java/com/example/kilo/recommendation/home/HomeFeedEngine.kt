package com.example.kilo.recommendation.home

import com.example.kilo.companion.ListeningEvent
import com.example.kilo.recommendation.genome.GenomeBuilder
import com.example.kilo.recommendation.genome.SimilarityGraph
import com.example.kilo.recommendation.genome.TrackGenome
import com.example.kilo.recommendation.user.AffinityCalculator
import com.example.kilo.recommendation.user.UserDNA
import java.util.Locale
import kotlin.math.max

/**
 * Deterministic Home ranking using the v3.2.0 Genome X weight split.
 * Input is limited to tracks already present in the local feed/candidate pool; this engine
 * performs no network, database, or player work.
 */
object HomeFeedEngine {
    const val USER_AFFINITY_WEIGHT = .35f
    const val LISTENING_HISTORY_WEIGHT = .25f
    const val FACTOR_MATCH_WEIGHT = .15f
    const val CONTEXT_WEIGHT = .10f
    const val SESSION_MEMORY_WEIGHT = .05f
    const val DISCOVERY_WEIGHT = .05f
    const val POPULARITY_WEIGHT = .05f

    data class ScoreBreakdown(
        val userAffinity: Float,
        val listeningHistory: Float,
        val factorMatch: Float,
        val context: Float,
        val sessionMemory: Float,
        val discovery: Float,
        val popularity: Float,
        val total: Float
    )

    data class RankedTrack(val genome: TrackGenome, val scores: ScoreBreakdown)

    fun rank(
        candidates: List<TrackGenome>,
        user: UserDNA,
        events: List<ListeningEvent>,
        factorTargets: Map<String, Float> = emptyMap(),
        contextFactors: Map<String, Float> = emptyMap(),
        popularityByTrack: Map<String, Float> = emptyMap(),
        limit: Int = candidates.size,
        nowMs: Long = System.currentTimeMillis()
    ): List<RankedTrack> {
        if (limit <= 0 || candidates.isEmpty()) return emptyList()
        val excluded = user.excludedTrackIds
        val recentEvents = events.asSequence()
            .filter { nowMs - it.atMs in 0L..HISTORY_WINDOW_MS }
            .toList()
        val byTrack = recentEvents.filter { it.trackId.isNotBlank() }.groupBy { it.trackId }
        val byArtist = recentEvents.filter { it.artist.isNotBlank() }
            .groupBy { GenomeBuilder.identity(it.artist) }
        val normalizedFactorTargets = factorTargets.ifEmpty { user.factorAffinity }

        return candidates.asSequence()
            .filter { it.trackId.isNotBlank() && it.trackId !in excluded }
            .distinctBy { it.trackId }
            .map { genome ->
                val affinity = AffinityCalculator.score(genome, user)
                val factor = factorScore(genome, normalizedFactorTargets)
                val context = factorScore(genome, contextFactors)
                val exactHistory = byTrack[genome.trackId].orEmpty()
                val relatedHistory = if (exactHistory.isNotEmpty()) exactHistory else byArtist[genome.artistKey].orEmpty()
                val history = historyScore(relatedHistory, nowMs)
                val sessionMemory = sessionMemoryScore(genome, recentEvents, nowMs)
                val discovery = (genome.discoveryScore * .60f + user.discoveryPreference * .40f)
                    .coerceIn(0f, 100f)
                // Popularity is intentionally neutral when the catalog provides no trustworthy
                // popularity signal; it must not turn this into a chart-first Home feed.
                val popularity = (popularityByTrack[genome.trackId] ?: 50f).coerceIn(0f, 100f)
                val total = affinity * USER_AFFINITY_WEIGHT +
                    history * LISTENING_HISTORY_WEIGHT +
                    factor * FACTOR_MATCH_WEIGHT +
                    context * CONTEXT_WEIGHT +
                    sessionMemory * SESSION_MEMORY_WEIGHT +
                    discovery * DISCOVERY_WEIGHT +
                    popularity * POPULARITY_WEIGHT
                RankedTrack(
                    genome,
                    ScoreBreakdown(affinity, history, factor, context, sessionMemory, discovery, popularity, total)
                )
            }
            .sortedWith(
                compareByDescending<RankedTrack> { it.scores.total }
                    .thenBy { stableTieBreak(it.genome.trackId) }
                    .thenBy { it.genome.trackId }
            )
            .take(limit)
            .toList()
    }

    private fun historyScore(events: List<ListeningEvent>, nowMs: Long): Float {
        if (events.isEmpty()) return 50f
        var weightedSignal = 0f
        var weightTotal = 0f
        events.forEach { event ->
            val ageDays = ((nowMs - event.atMs).coerceAtLeast(0L) / DAY_MS).toFloat()
            val recency = (1f - ageDays / 120f).coerceIn(.05f, 1f)
            val duration = event.durationMs.coerceAtLeast(1L)
            val completion = (event.listenedMs.toFloat() / duration).coerceIn(0f, 1f)
            val signal = when (event.action.lowercase(Locale.ROOT)) {
                "like", "replay" -> 28f
                "complete" -> 22f
                "listen" -> 8f + completion * 14f
                "skip", "not_for_mood" -> -24f
                "unlike" -> -18f
                else -> if (completion >= .8f) 12f else 0f
            }
            weightedSignal += signal * recency
            weightTotal += recency
        }
        if (weightTotal <= 0f) return 50f
        return (50f + weightedSignal / max(1f, weightTotal) * .65f).coerceIn(0f, 100f)
    }

    private fun factorScore(genome: TrackGenome, targets: Map<String, Float>): Float {
        if (targets.isEmpty()) return 50f
        if (targets.keys.none { genome.evidence(it) > 0f }) return 50f
        return SimilarityGraph.factorSimilarity(genome, targets) * 100f
    }

    private fun sessionMemoryScore(genome: TrackGenome, events: List<ListeningEvent>, nowMs: Long): Float {
        if (events.isEmpty()) return 50f
        val recent = events.asReversed().take(24)
        val matching = recent.filter { event ->
            (event.artist.isNotBlank() && GenomeBuilder.identity(event.artist) == genome.artistKey) ||
                event.genres.any { label -> genome.genres.keys.any { it.equals(label.trim().lowercase(Locale.ROOT), true) } }
        }
        if (matching.isEmpty()) return 50f
        val latest = matching.maxOfOrNull { it.atMs } ?: return 50f
        val ageHours = ((nowMs - latest).coerceAtLeast(0L) / HOUR_MS).toFloat()
        return (50f + (24f - ageHours.coerceAtMost(24f)) * .8f).coerceIn(50f, 70f)
    }

    private fun stableTieBreak(id: String): Int = id.lowercase(Locale.ROOT).hashCode()

    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L
    private const val HISTORY_WINDOW_MS = 180L * DAY_MS
}
