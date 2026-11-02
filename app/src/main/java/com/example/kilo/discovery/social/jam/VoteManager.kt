package com.example.kilo.discovery.social.jam

import com.example.kilo.discovery.JamSession

class VoteManager {
    fun vote(session: JamSession, trackId: String, delta: Int = 1): JamSession {
        if (session.queue.none { it.id == trackId || it.videoId == trackId }) return session
        return session.copy(votesByTrack = session.votesByTrack + (trackId to ((session.votesByTrack[trackId] ?: 0) + delta)))
    }
    fun nextByVotes(session: JamSession): List<String> = session.queue.sortedByDescending { session.votesByTrack[it.videoId.ifBlank { it.id }] ?: 0 }.map { it.videoId.ifBlank { it.id } }
}
