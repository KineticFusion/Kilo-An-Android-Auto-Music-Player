package com.example.kilo.playback.preparation

/** Bounded in-memory standby cache: the current item plus one upcoming item only. */
class PreparedTrackCache {
    @Volatile private var next: PreparedTrack? = null
    fun get(mediaId: String): PreparedTrack? = next?.takeIf { it.mediaId == mediaId }
    @Synchronized fun put(value: PreparedTrack) { next = value }
    @Synchronized fun clear() { next = null }
}
