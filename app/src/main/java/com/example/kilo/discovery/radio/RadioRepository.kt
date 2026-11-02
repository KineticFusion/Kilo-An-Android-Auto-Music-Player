package com.example.kilo.discovery.radio

import com.example.kilo.SongItemClean

class RadioRepository {
    private val sessions = linkedMapOf<String, List<SongItemClean>>()
    @Synchronized fun save(sessionId: String, queue: List<SongItemClean>) { sessions[sessionId] = queue.take(100) }
    @Synchronized fun load(sessionId: String): List<SongItemClean> = sessions[sessionId].orEmpty()
}
