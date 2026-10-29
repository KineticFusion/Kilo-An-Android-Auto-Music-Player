package com.example.kilo.search.preparation

import com.example.kilo.playback.prepared.PreparedTrackRegistry

/** Search cache adapter over the single prepared-track registry; source TTL remains authoritative. */
object SearchPreparedCache {
    fun get(trackId: String): SearchPreparedTrack? = PreparedTrackRegistry.get(trackId)
    fun ready(trackId: String): SearchPreparedTrack? = get(trackId)?.takeIf { it.isReady() }
}
