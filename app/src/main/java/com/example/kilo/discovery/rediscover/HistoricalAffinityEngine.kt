package com.example.kilo.discovery.rediscover

import com.example.kilo.SongItemClean
import com.example.kilo.companion.ListeningEvent

class HistoricalAffinityEngine {
    fun group(catalog: List<SongItemClean>, events: List<ListeningEvent>, recentIds: Set<String>, nowMs: Long): Map<String, List<SongItemClean>> {
        val byId = catalog.associateBy { it.id }.toMutableMap().apply { catalog.forEach { put(it.videoId, it) } }
        val older = events.filter { nowMs - it.atMs >= 30L * 86_400_000L }
        val forgotten = older.groupBy { it.trackId }.keys.mapNotNull(byId::get).filterNot { it.id in recentIds || it.videoId in recentIds }
        val loved = older.filter { it.action in setOf("like", "favorite", "complete", "replay") }.groupBy { it.trackId }
            .entries.sortedByDescending { it.value.size }.mapNotNull { byId[it.key] }
            .filterNot { it.id in recentIds || it.videoId in recentIds }
        val artists = older.groupBy { it.artist }.entries.sortedByDescending { it.value.size }.mapNotNull { entry ->
            catalog.firstOrNull { it.artist.equals(entry.key, true) }
        }.filterNot { it.id in recentIds || it.videoId in recentIds }
        return linkedMapOf(
            "forgotten" to forgotten.distinctBy { it.id }.take(20),
            "loved" to loved.distinctBy { it.id }.take(20),
            "artists" to artists.distinctBy { it.id }.take(20)
        )
    }
}
