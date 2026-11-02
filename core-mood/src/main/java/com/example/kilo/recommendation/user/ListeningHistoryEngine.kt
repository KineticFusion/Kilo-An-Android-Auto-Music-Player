package com.example.kilo.recommendation.user

import com.example.kilo.companion.ListeningEvent

object ListeningHistoryEngine {
    fun recent(events: List<ListeningEvent>, now: Long = System.currentTimeMillis(), windowDays: Int = 180) =
        events.filter { now - it.atMs in 0..(windowDays.toLong() * DAY_MS) }

    fun skips(events: List<ListeningEvent>) = events.filter { it.action == "skip" }
    fun replays(events: List<ListeningEvent>) = events.filter { it.action == "replay" }
    fun completions(events: List<ListeningEvent>) = events.filter {
        it.action == "complete" || (it.action == "listen" && it.durationMs > 0 && it.listenedMs >= it.durationMs * .8)
    }

    private const val DAY_MS = 86_400_000L
}
