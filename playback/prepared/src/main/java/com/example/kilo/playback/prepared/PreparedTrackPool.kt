package com.example.kilo.playback.prepared

import java.util.LinkedHashMap

enum class PreparedReadinessState {
    DISCOVERED,
    PREPARING,
    SOURCE_READY,
    MEDIASOURCE_READY,
    PLAYBACK_READY,
    FAILED
}

data class PreparedTrackPoolEntry(
    val track: PreparedPlayableTrack,
    val readiness: PreparedReadinessState,
    val preparedTimestampMs: Long,
    val lastAccessTimestampMs: Long
)

/** Bounded, process-memory-only pool of visible tracks and their validated source state. */
object PreparedTrackPool {
    private const val MAX_ENTRIES = 48
    private const val TTL_MS = 15 * 60 * 1000L

    private val entries = object : LinkedHashMap<String, PreparedTrackPoolEntry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreparedTrackPoolEntry>?): Boolean =
            size > MAX_ENTRIES
    }

    fun put(track: PreparedPlayableTrack, state: PreparedReadinessState): PreparedTrackPoolEntry? = synchronized(entries) {
        val id = track.trackId
        if (id.isBlank() || id.startsWith("radio:")) return@synchronized null
        val now = System.currentTimeMillis()
        val previous = entries[id]
        val retainedTrack = track.copy(
            mediaItem = track.mediaItem ?: previous?.track?.mediaItem,
            streamInfo = track.streamInfo ?: previous?.track?.streamInfo,
            artwork = track.artwork.ifBlank { previous?.track?.artwork.orEmpty() }
        )
        val resolvedState = when {
            state == PreparedReadinessState.PLAYBACK_READY && !retainedTrack.isReady(now) -> PreparedReadinessState.PREPARING
            else -> state
        }
        PreparedTrackPoolEntry(
            track = retainedTrack,
            readiness = resolvedState,
            preparedTimestampMs = previous?.preparedTimestampMs ?: track.preparedTimestampMs.takeIf { it > 0L } ?: now,
            lastAccessTimestampMs = now
        ).also { entries[id] = it }
    }

    fun get(trackId: String): PreparedTrackPoolEntry? = synchronized(entries) {
        val entry = entries[trackId] ?: return@synchronized null
        val now = System.currentTimeMillis()
        val expiredByTtl = now - entry.lastAccessTimestampMs > TTL_MS
        val expiredSource = entry.track.expiryTimestampMs > 0L && entry.track.expiryTimestampMs <= now
        if (expiredByTtl || expiredSource) {
            entries.remove(trackId)
            return@synchronized null
        }
        entry.copy(lastAccessTimestampMs = now).also { entries[trackId] = it }
    }

    fun mark(trackId: String, state: PreparedReadinessState): PreparedTrackPoolEntry? = synchronized(entries) {
        val current = entries[trackId] ?: return@synchronized null
        val now = System.currentTimeMillis()
        val safeState = if (state == PreparedReadinessState.PLAYBACK_READY && !current.track.isReady(now)) {
            PreparedReadinessState.PREPARING
        } else state
        current.copy(readiness = safeState, lastAccessTimestampMs = now).also { entries[trackId] = it }
    }

    fun remove(trackId: String) = synchronized(entries) { entries.remove(trackId); Unit }
    fun clear() = synchronized(entries) { entries.clear() }
    fun snapshot(): List<PreparedTrackPoolEntry> = synchronized(entries) { entries.values.toList() }
}
