package com.example.kilo.recommendation.mood

import com.example.kilo.recommendation.genome.TrackGenome
import kotlin.math.abs

/** A persistent factor target used by mood, vibe, and activity sessions. */
data class FactorLock(
    val key: String,
    val factors: Map<String, Float>,
    val strictness: Float = 0.80f,
    val relatedFloor: Float = 0.60f
)

object FactorLockEngine {
    fun lock(
        key: String,
        factors: Map<String, Float>,
        strictness: Float = 0.80f,
        relatedFloor: Float = 0.60f
    ) = FactorLock(
        key = key.trim().lowercase(),
        factors = factors.mapKeys { MoodVector.canonicalFactor(it.key) }
            .mapValues { it.value.coerceIn(0f, 100f) },
        strictness = strictness.coerceIn(0.75f, 0.85f),
        relatedFloor = relatedFloor.coerceIn(0.50f, 0.75f)
    )

    /** Compares numeric track factors only; no labels or title text enter the score. */
    fun match(genome: TrackGenome, lock: FactorLock): Float {
        if (lock.factors.isEmpty()) return 1f
        val observed = lock.factors.mapNotNull { (name, target) ->
            val confidence = genome.evidence(name)
            if (confidence <= 0f) null else (1f - abs(genome.factor(name) - target) / 100f).coerceIn(0f, 1f) to confidence
        }
        if (observed.isEmpty()) return 0f
        val coverage = (observed.sumOf { it.second.toDouble() } / lock.factors.size).toFloat().coerceIn(0f, 1f)
        val measuredFit = (observed.sumOf { (fit, confidence) -> (fit * confidence).toDouble() } /
            observed.sumOf { it.second.toDouble() }).toFloat().coerceIn(0f, 1f)
        // Sparse evidence is not allowed to impersonate a confident mood match.
        return (measuredFit * kotlin.math.sqrt(coverage)).coerceIn(0f, 1f)
    }

    fun coverage(genome: TrackGenome, factors: Map<String, Float>): Float {
        if (factors.isEmpty()) return 1f
        return (factors.keys.sumOf { genome.evidence(it).toDouble() } / factors.size).toFloat().coerceIn(0f, 1f)
    }

    fun isStrict(genome: TrackGenome, lock: FactorLock) = match(genome, lock) >= lock.strictness
    fun isRelated(genome: TrackGenome, lock: FactorLock) = match(genome, lock) >= lock.relatedFloor
}

/** Vibe sessions use the same factor lock without requiring a mood or genre category. */
object VibeLockEngine {
    fun lock(key: String, factors: Map<String, Float>) = FactorLockEngine.lock(key, factors)
}
