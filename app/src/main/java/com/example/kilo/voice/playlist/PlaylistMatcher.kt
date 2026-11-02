package com.example.kilo.voice.playlist

import java.util.Locale

object PlaylistMatcher {
    fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    fun score(query: String, candidate: String): Float {
        val q = normalize(query)
        val c = normalize(candidate)
        if (q.isBlank() || c.isBlank()) return 0f
        if (q == c) return 1f
        if (q.contains(c) || c.contains(q)) return 0.9f
        val left = q.split(' ').toSet()
        val right = c.split(' ').toSet()
        val overlap = left.intersect(right).size.toFloat()
        return (2f * overlap / (left.size + right.size).coerceAtLeast(1)).coerceIn(0f, 0.89f)
    }

    fun bestMatch(query: String, candidates: Collection<String>, minimumScore: Float = 0.48f): String? =
        candidates.map { it to score(query, it) }.maxByOrNull { it.second }
            ?.takeIf { it.second >= minimumScore }?.first
}
