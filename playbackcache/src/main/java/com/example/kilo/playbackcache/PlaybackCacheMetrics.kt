package com.example.kilo.playbackcache

import androidx.media3.datasource.cache.Cache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class PlaybackCacheSnapshot(
    val resolvedUrlHits: Long,
    val resolvedUrlMisses: Long,
    val segmentBytesFound: Long
) {
    val resolvedHitRatePercent: Int
        get() = if (resolvedUrlHits + resolvedUrlMisses == 0L) 0
        else (resolvedUrlHits * 100L / (resolvedUrlHits + resolvedUrlMisses)).toInt()
}

/** Metrics only: actual memory and disk caches continue to be owned by core-streaming. */
object PlaybackCacheMetrics {
    private val observedTracks = ConcurrentHashMap<String, Boolean>()
    private val segmentCoverage = ConcurrentHashMap<String, Long>()

    fun recordResolvedUrl(trackId: String, hit: Boolean) {
        if (trackId.isBlank()) return
        observedTracks.putIfAbsent(trackId, hit)
    }

    fun recordSegmentCoverage(trackId: String, bytes: Long) {
        if (trackId.isNotBlank() && bytes > 0L) segmentCoverage[trackId] = bytes
    }

    fun snapshot(): PlaybackCacheSnapshot {
        val hits = observedTracks.values.count { it }.toLong()
        val misses = observedTracks.size.toLong() - hits
        return PlaybackCacheSnapshot(hits, misses, segmentCoverage.values.sum())
    }
}

/** Reads contiguous prefetched bytes without downloading or changing cache state. */
object AudioSegmentCache {
    fun cachedBytes(cache: Cache, key: String, maxBytes: Long = 30L * 1024L * 1024L): Long =
        runCatching { cache.getCachedLength(key, 0L, maxBytes).coerceAtLeast(0L) }.getOrDefault(0L)
}
