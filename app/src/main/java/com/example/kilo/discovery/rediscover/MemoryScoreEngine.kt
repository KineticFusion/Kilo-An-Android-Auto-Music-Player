package com.example.kilo.discovery.rediscover

import com.example.kilo.companion.ListeningEvent

class MemoryScoreEngine {
    fun score(trackId: String, events: List<ListeningEvent>, nowMs: Long): Float {
        val history = events.filter { it.trackId == trackId }
        if (history.isEmpty()) return 0f
        val last = history.maxOf { it.atMs }
        val ageDays = ((nowMs - last).coerceAtLeast(0L) / 86_400_000L).toFloat()
        val repeats = history.size.coerceAtMost(12) / 12f
        val positive = history.count { it.action in setOf("complete", "listen", "like", "favorite", "replay", "discovery_keep") } / 8f
        val skipped = history.count { it.action == "skip" } / 6f
        return (ageDays / 120f).coerceIn(0f, 1f) * 0.48f + repeats * 0.35f + positive * 0.17f - skipped
    }
}
