package com.example.kilo.recommendation.mood

import com.example.kilo.recommendation.genome.SimilarityGraph
import com.example.kilo.recommendation.genome.TrackGenome

data class MoodLock(
    val vector: MoodVector,
    val strictness: Float = 0.75f,
    val relatedFloor: Float = 0.58f
)

enum class MoodLockState { LOCKED, RELAXED, EXPANDING, DISCOVERY }

object MoodLockEngine {
    fun lock(vector: MoodVector, strictness: Float = 0.75f) = MoodLock(
        vector = vector,
        strictness = strictness.coerceIn(0.75f, 0.85f),
        relatedFloor = 0.58f
    )

    /** Mood fit is factor-first; genre vector is a secondary signal, never a text search. */
    fun match(genome: TrackGenome, lock: MoodLock): Float {
        if (lock.vector.factors.isEmpty() && lock.vector.genres.isEmpty()) return 0f
        val factorLock = FactorLockEngine.lock(lock.vector.key, lock.vector.factors, lock.strictness, lock.relatedFloor)
        val factorFit = FactorLockEngine.match(genome, factorLock)
        val factorCoverage = FactorLockEngine.coverage(genome, lock.vector.factors)
        val genreEvidence = genome.genres.isNotEmpty()
        if (lock.vector.genres.isEmpty() || !genreEvidence) return factorFit
        val genreFit = SimilarityGraph.vectorSimilarity(genome.genres, lock.vector.genres)
        if (factorCoverage <= 0f) return genreFit
        val factorWeight = .78f * factorCoverage
        val genreWeight = .22f + (.78f * (1f - factorCoverage))
        return ((factorFit * factorWeight + genreFit * genreWeight) /
            (factorWeight + genreWeight)).coerceIn(0f, 1f)
    }

    fun stateFor(match: Float, strictness: Float = 0.75f): MoodLockState = when {
        match >= strictness -> MoodLockState.LOCKED
        match >= 0.58f -> MoodLockState.RELAXED
        match >= 0.50f -> MoodLockState.EXPANDING
        else -> MoodLockState.DISCOVERY
    }
}
