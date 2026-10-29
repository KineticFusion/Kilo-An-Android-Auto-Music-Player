package com.example.kilo.playback.stability

import androidx.media3.common.Player
import java.util.ArrayDeque

/** Samples the shared player every 250 ms and retains only the most recent 20 states. */
object PlaybackWatchdog {
    private const val MAX_SAMPLES = 20
    private val lock = Any()
    private val samples = ArrayDeque<PlaybackHealthSnapshot>(MAX_SAMPLES)

    fun record(snapshot: PlaybackHealthSnapshot) = synchronized(lock) {
        if (snapshot.mediaId.isBlank()) return@synchronized
        if (samples.size == MAX_SAMPLES) samples.removeFirst()
        samples.addLast(snapshot)
    }

    fun latest(mediaId: String? = null): PlaybackHealthSnapshot? = synchronized(lock) {
        if (mediaId == null) samples.lastOrNull() else samples.lastOrNull { it.mediaId == mediaId }
    }

    fun recent(): List<PlaybackHealthSnapshot> = synchronized(lock) { samples.toList() }

    /**
     * Returns the latest two recent, actively playing samples for an item. Used at the
     * unexpected-IDLE boundary, where the live player is already idle and can no longer
     * demonstrate forward progress itself.
     */
    fun recentHealthyProgress(
        mediaId: String,
        nowElapsedMs: Long,
        maxAgeMs: Long = 1_500L
    ): Pair<PlaybackHealthSnapshot, PlaybackHealthSnapshot>? = synchronized(lock) {
        val active = samples.asSequence()
            .filter { it.mediaId == mediaId }
            .filter {
                it.playerState == Player.STATE_READY && it.isPlaying && it.playWhenReady &&
                    it.sourceValid && it.bufferedMs > 5_000L &&
                    it.sourceState !in setOf("EXPIRING", "FAILED", "INVALID") &&
                    nowElapsedMs - it.elapsedRealtimeMs in 0..maxAgeMs
            }
            .toList()
        if (active.size < 2) return@synchronized null
        val previous = active[active.lastIndex - 1]
        val current = active.last()
        if (current.elapsedRealtimeMs - previous.elapsedRealtimeMs > 1_000L ||
            current.positionMs <= previous.positionMs
        ) return@synchronized null
        previous to current
    }
}
