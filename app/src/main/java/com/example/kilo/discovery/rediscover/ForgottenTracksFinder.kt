package com.example.kilo.discovery.rediscover

import com.example.kilo.SongItemClean
import com.example.kilo.companion.ListeningEvent
import com.example.kilo.discovery.discoveryKey

class ForgottenTracksFinder(private val memory: MemoryScoreEngine = MemoryScoreEngine()) {
    fun find(catalog: List<SongItemClean>, events: List<ListeningEvent>, recentIds: Set<String>, nowMs: Long, limit: Int = 20): List<SongItemClean> {
        val eventsById = events.groupBy { it.trackId }
        return catalog.asSequence().filter { it.videoId.isNotBlank() || it.isDownloaded }.distinctBy(::discoveryKey)
            .filterNot { it.id in recentIds || it.videoId in recentIds }
            .filter { song -> eventsById.containsKey(song.id) || eventsById.containsKey(song.videoId) }
            .sortedByDescending { song -> maxOf(memory.score(song.id, events, nowMs), memory.score(song.videoId, events, nowMs)) }
            .take(limit.coerceIn(1, 40)).toList()
    }
}
