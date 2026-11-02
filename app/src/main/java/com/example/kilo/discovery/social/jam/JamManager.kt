package com.example.kilo.discovery.social.jam

import com.example.kilo.SongItemClean
import com.example.kilo.discovery.JamSession
import java.util.UUID

class JamManager(private val queue: CollaborativeQueue = CollaborativeQueue()) {
    fun create(name: String, hostId: String, initialQueue: List<SongItemClean>): JamSession =
        JamSession("jam-${UUID.randomUUID()}", name.trim().ifBlank { "Listening Session" }.take(80), hostId, System.currentTimeMillis(), initialQueue.distinctBy { it.videoId.ifBlank { it.id } })
    fun add(session: JamSession, song: SongItemClean): JamSession = queue.add(session, song)
    fun end(session: JamSession): JamSession = session.copy(active = false)
}
