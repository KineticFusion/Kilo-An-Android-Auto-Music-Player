package com.example.kilo.recommendation.ranking

import com.example.kilo.recommendation.genome.SimilarityGraph
import com.example.kilo.recommendation.genome.TrackGenome
import com.example.kilo.recommendation.genre.GenreConsistencyManager
import com.example.kilo.recommendation.genre.GenreLock
import com.example.kilo.recommendation.genre.GenreLockEngine
import com.example.kilo.recommendation.mood.FactorLock
import com.example.kilo.recommendation.mood.FactorLockEngine
import com.example.kilo.recommendation.mood.MoodConsistencyManager
import com.example.kilo.recommendation.mood.MoodLock
import com.example.kilo.recommendation.mood.MoodLockEngine
import com.example.kilo.recommendation.session.SessionState
import com.example.kilo.recommendation.user.AffinityCalculator
import com.example.kilo.recommendation.user.UserDNA
import kotlin.math.ceil

data class RankBreakdown(
    val mood: Float,
    val factor: Float,
    val session: Float,
    val affinity: Float,
    val discovery: Float,
    val diversity: Float,
    val context: Float,
    val total: Float
)

data class RankedGenome(val genome: TrackGenome, val breakdown: RankBreakdown)

data class RankingRequest(
    val moodLock: MoodLock? = null,
    val genreLock: GenreLock? = null,
    val factorLock: FactorLock? = null,
    val factors: Map<String, Float> = emptyMap(),
    val user: UserDNA = UserDNA(),
    val session: SessionState? = null,
    val contextFactors: Map<String, Float> = emptyMap(),
    val playedIds: Set<String> = emptySet(),
    val discoveryRatio: Float = .10f
)

/** Seven score weights are intentionally fixed to the Genome X architecture. */
object RecommendationRanker {
    private data class LockMatch(val score: (TrackGenome) -> Float, val strict: Float, val related: Float)

    fun rank(candidates: List<TrackGenome>, request: RankingRequest, limit: Int = candidates.size): List<RankedGenome> {
        if (limit <= 0) return emptyList()
        val inSession = request.session?.let { (it.trackHistory + it.queuedTrackIds).toSet() }.orEmpty()
        val valid = CandidateGenerator.from(candidates, request.user.excludedTrackIds + inSession)
        val lock = lockMatch(request)
        val compatible = lock?.let { active -> valid.filter { active.score(it) >= active.related } } ?: valid
        // Never escape an active mood, genre, or factor lock to fill a batch with unrelated songs.
        if (compatible.isEmpty()) return emptyList()

        // Prefer tracks outside the recent-play window. Only revisit recent tracks after the fresh pool is exhausted.
        val recentFree = compatible.filterNot { it.trackId in request.playedIds }
        val pool = if (recentFree.isNotEmpty()) recentFree else compatible
        val ranked = pool.map { genome ->
            val moodScore = when {
                request.moodLock != null -> MoodConsistencyManager.match(genome, request.moodLock) * 100f
                request.genreLock != null -> GenreConsistencyManager.match(genome, request.genreLock) * 100f
                else -> 50f
            }
            val targetFactors = request.factors.ifEmpty {
                request.factorLock?.factors
                    ?: request.moodLock?.vector?.factors
                    ?: request.genreLock?.vector?.values.orEmpty()
            }
            val factorScore = if (targetFactors.isEmpty()) 50f
                else SimilarityScorer.factors(genome, targetFactors) * 100f

            val recentArtists = request.session?.artistHistory?.takeLast(15).orEmpty()
            val recentAlbums = request.session?.albumHistory.orEmpty()
            val repeatedArtist = genome.artistKey.isNotBlank() && genome.artistKey in recentArtists
            val repeatedAlbum = genome.albumKey.isNotBlank() && genome.albumKey in recentAlbums
            val sessionHistory = request.session?.trackHistory.orEmpty()
            val sessionScore = if (sessionHistory.isEmpty()) genome.sessionCompatibility else {
                (if (repeatedArtist) 35f else 100f) * .25f +
                    genome.sessionCompatibility * .35f + moodScore * .40f
            }
            val affinity = AffinityCalculator.score(
                genome,
                request.user,
                request.moodLock?.vector?.key ?: request.genreLock?.vector?.key ?: request.factorLock?.key.orEmpty()
            )
            val discovery = genome.discoveryScore * .60f + request.user.discoveryPreference * .40f
            val diversity = when { repeatedArtist -> 20f; repeatedAlbum -> 35f; else -> 100f }
            val context = if (request.contextFactors.isEmpty()) 50f
                else SimilarityGraph.factorSimilarity(genome, request.contextFactors) * 100f

            val total = moodScore * .25f + factorScore * .20f + sessionScore * .15f +
                affinity * .15f + discovery * .10f + diversity * .10f + context * .05f
            RankedGenome(genome, RankBreakdown(moodScore, factorScore, sessionScore, affinity, discovery, diversity, context, total))
        }.sortedByDescending { it.breakdown.total }

        val allocated = DiscoveryAllocator.allocate(ranked, limit)
        val lockBalanced = lock?.let { active -> enforceStrictShare(allocated, ranked, active, limit) } ?: allocated
        return DiversityEngine.select(lockBalanced, request.session, limit, request.user.preferredLanguageShare)
    }

    private fun lockMatch(request: RankingRequest): LockMatch? {
        val moodLock = request.moodLock
        val genreLock = request.genreLock
        val factorLock = request.factorLock
        return when {
        moodLock != null -> LockMatch(
            score = { MoodConsistencyManager.match(it, moodLock) },
            strict = moodLock.strictness,
            related = moodLock.relatedFloor
        )
        genreLock != null -> LockMatch(
            score = { GenreConsistencyManager.match(it, genreLock) },
            strict = genreLock.strictness,
            related = genreLock.relatedFloor
        )
        factorLock != null -> LockMatch(
            score = { FactorLockEngine.match(it, factorLock) },
            strict = factorLock.strictness,
            related = factorLock.relatedFloor
        )
        else -> null
        }
    }

    /** Keep 80% strictly inside the selected vector; use only compatible tracks for the rest. */
    private fun enforceStrictShare(
        allocated: List<RankedGenome>,
        ranked: List<RankedGenome>,
        lock: LockMatch,
        limit: Int
    ): List<RankedGenome> {
        val candidates = (allocated + ranked).distinctBy { it.genome.trackId }
        val strictQuota = ceil(limit * .80f).toInt().coerceAtMost(limit)
        val strict = candidates.filter { lock.score(it.genome) >= lock.strict }
        val related = candidates.filter { lock.score(it.genome) >= lock.related && lock.score(it.genome) < lock.strict }
        val chosen = mutableListOf<RankedGenome>()
        chosen += strict.take(strictQuota)
        chosen += related.take((limit - chosen.size).coerceAtLeast(0))
        if (chosen.size < limit) chosen += strict.drop(strictQuota).take(limit - chosen.size)
        return chosen
    }
}
