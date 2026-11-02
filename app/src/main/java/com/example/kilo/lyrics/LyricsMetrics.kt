package com.example.kilo.lyrics

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

data class LyricsMetricsSnapshot(
    val cacheHits: Long,
    val cacheMisses: Long,
    val providerLoads: Long,
    val sharedCards: Long,
    val lastLoadDurationMs: Long,
    val lastSource: String
)

object LyricsMetrics {
    private const val TAG = "KiloLyricsMetrics"
    private val cacheHits = AtomicLong()
    private val cacheMisses = AtomicLong()
    private val providerLoads = AtomicLong()
    private val sharedCards = AtomicLong()
    @Volatile private var lastLoadDurationMs = 0L
    @Volatile private var lastSource = "none"

    fun loaded(trackId: String, source: String, durationMs: Long, lines: Int) {
        providerLoads.incrementAndGet()
        lastLoadDurationMs = durationMs
        lastSource = source
        Log.d(TAG, "lyrics_loaded track=${trackId.hashCode()} source=$source durationMs=$durationMs lines=$lines")
    }
    fun lookup(trackId: String, cache: String, startedAt: Long) {
        if (cache == "memory" || cache == "disk" || cache == "provider-memory") cacheHits.incrementAndGet()
        if (cache == "miss") cacheMisses.incrementAndGet()
        Log.d(TAG, "lyrics_lookup track=${trackId.hashCode()} cache=$cache elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
    }
    fun share(trackId: String, success: Boolean) {
        if (success) sharedCards.incrementAndGet()
        Log.d(TAG, "lyrics_share track=${trackId.hashCode()} success=$success")
    }

    fun snapshot() = LyricsMetricsSnapshot(cacheHits.get(), cacheMisses.get(), providerLoads.get(), sharedCards.get(), lastLoadDurationMs, lastSource)
}
