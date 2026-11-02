package com.example.kilo.discovery.social.jam

import com.example.kilo.SongItemClean
import com.example.kilo.discovery.JamSession

class CollaborativeQueue {
    fun add(session: JamSession, song: SongItemClean): JamSession {
        val key = song.videoId.ifBlank { song.id }
        return if (session.queue.any { it.videoId.ifBlank { it.id } == key }) session
        else session.copy(queue = session.queue + song)
    }
    fun remove(session: JamSession, trackId: String): JamSession = session.copy(queue = session.queue.filterNot { it.id == trackId || it.videoId == trackId })
}
