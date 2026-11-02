package com.example.kilo.discovery.continuation

import com.example.kilo.SongItemClean

class ContinuationRepository {
    private val queues = linkedMapOf<String, List<SongItemClean>>()
    @Synchronized fun put(sessionId: String, tracks: List<SongItemClean>) { queues[sessionId] = tracks.take(10) }
    @Synchronized fun take(sessionId: String): List<SongItemClean> = queues.remove(sessionId).orEmpty()
}
