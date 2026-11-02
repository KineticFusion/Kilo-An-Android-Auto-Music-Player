package com.example.kilo.recommendation.user

import com.example.kilo.recommendation.genome.TrackGenome
import kotlin.math.abs

object AffinityCalculator {
    fun score(genome: TrackGenome, dna: UserDNA, lockKey: String = ""): Float {
        val direct = if (genome.trackId in dna.likedTrackIds) 100f else 0f
        val artist = dna.artistAffinity[genome.artistKey]?.let { (50f + it * 2.5f).coerceIn(0f, 100f) } ?: 50f
        fun vector(candidate: Map<String, Float>, taste: Map<String, Float>): Float {
            if (candidate.isEmpty() || taste.isEmpty()) return 50f
            val weights = candidate.mapNotNull { (key, value) -> taste[key]?.let { value * it.coerceAtLeast(0f) } }
            return if (weights.isEmpty()) 35f else (weights.sum() / candidate.values.sum().coerceAtLeast(1f)).coerceIn(0f, 100f)
        }
        val genre = vector(genome.genres, dna.genreAffinity)
        val language = vector(genome.languages, dna.languageAffinity)
        val factor = if (dna.factorAffinity.isEmpty()) 50f else {
            val matches = dna.factorAffinity.mapNotNull { (key, target) ->
                genome.factors[key]?.takeIf { genome.evidence(key) > 0f }?.let { 1f - abs(it - target) / 100f }
            }
            if (matches.isEmpty()) 50f else matches.average().toFloat() * 100f
        }
        val mood = dna.moodAffinity[lockKey.trim().lowercase()]?.let { (50f + it * 5f).coerceIn(0f, 100f) } ?: 50f
        val cluster = if (genome.trackId in dna.tasteClusterTrackIds) 90f else 50f
        return (direct * .20f + artist * .18f + genre * .19f + language * .08f + factor * .15f + mood * .10f + cluster * .10f)
            .coerceIn(0f, 100f)
    }
}
