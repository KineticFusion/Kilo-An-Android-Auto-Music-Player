package com.example.kilo.discovery.radio

import com.example.kilo.SongItemClean

class PlaylistRadioEngine(private val queueBuilder: RadioQueueBuilder = RadioQueueBuilder()) {
    fun start(seed: List<SongItemClean>, catalog: List<SongItemClean>): List<SongItemClean> =
        (seed + queueBuilder.next(seed, catalog, seed.mapTo(mutableSetOf()) { it.videoId.ifBlank { it.id } }))
            .distinctBy { it.videoId.ifBlank { it.id } }
}
class InfiniteRadioManager {
    private val seenBySession = mutableMapOf<String, MutableSet<String>>()
    @Synchronized fun seen(sessionId: String): Set<String> = seenBySession[sessionId]?.toSet().orEmpty()
    @Synchronized fun remember(sessionId: String, songs: List<SongItemClean>) {
        val seen = seenBySession.getOrPut(sessionId) { linkedSetOf() }
        songs.forEach { seen += it.videoId.ifBlank { it.id } }
    }
}
