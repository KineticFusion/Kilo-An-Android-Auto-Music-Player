package com.example.kilo.playbackrecovery

import android.util.Log
import com.example.kilo.networkmonitor.NetworkHealthMonitor
import com.example.kilo.playbackcache.PlaybackCacheMetrics
import java.util.ArrayDeque

data class PlaybackDiagnosticEvent(
    val atMs: Long,
    val name: String,
    val mediaId: String,
    val details: String
)

/** Bounded, in-memory diagnostics; no track history or audio is persisted. */
object PlaybackDiagnostics {
    private const val TAG = "KiloPlaybackReliability"
    private const val MAX_EVENTS = 400
    private val events = ArrayDeque<PlaybackDiagnosticEvent>()
    private var bufferEvents = 0L
    private var recoveryEvents = 0L
    private var playerErrors = 0L
    private var prefetchBatches = 0L

    @Synchronized
    fun record(name: String, mediaId: String = "", details: String = "") {
        val event = PlaybackDiagnosticEvent(System.currentTimeMillis(), name, mediaId, details)
        if (events.size == MAX_EVENTS) events.removeFirst()
        events.addLast(event)
        when (name) {
            "buffer_start" -> bufferEvents++
            "buffer_recovered", "existing_retry_active" -> recoveryEvents++
            "player_error" -> playerErrors++
            "prefetch_scheduled" -> prefetchBatches++
        }
        val message = "event=$name id=$mediaId $details".trim()
        if (name == "player_error" || name == "buffer_stall") Log.w(TAG, message)
        else Log.d(TAG, message)
    }

    @Synchronized
    fun exportReport(): String {
        val network = NetworkHealthMonitor.snapshot()
        val cache = PlaybackCacheMetrics.snapshot()
        return buildString {
            appendLine("KILO Playback Reliability Report")
            appendLine("Network: ${network.transport} / ${network.grade}; estimated downstream ${network.downstreamKbps} kbps; validated=${network.validated}")
            appendLine("Resolved URL cache: ${cache.resolvedUrlHits} hits, ${cache.resolvedUrlMisses} misses, ${cache.resolvedHitRatePercent}% hit rate")
            appendLine("Observed audio segment bytes: ${cache.segmentBytesFound}")
            appendLine("Buffer events: $bufferEvents; recovery events: $recoveryEvents; player errors: $playerErrors; prefetch batches: $prefetchBatches")
            appendLine("Recent events:")
            events.forEach { appendLine("${it.atMs} ${it.name} ${it.mediaId} ${it.details}".trim()) }
        }
    }
}
