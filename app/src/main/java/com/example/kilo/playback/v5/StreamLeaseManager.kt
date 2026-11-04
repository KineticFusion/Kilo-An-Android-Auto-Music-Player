package com.example.kilo.playback.v5

import android.net.Uri
import com.example.kilo.streaming.StreamInfo
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Tracks provider URL leases and marks expiring leases for refresh before reuse. */
object StreamLeaseManager {
    private const val REFRESH_SAFETY_WINDOW_MS = 10 * 60_000L
    private const val UNKNOWN_EXPIRY_TTL_MS = 5 * 60 * 60_000L

    data class Lease(
        val mediaId: String,
        val info: StreamInfo,
        val storedAtMs: Long,
        val refreshAfterMs: Long,
        val generation: Long
    )

    private val leases = ConcurrentHashMap<String, Lease>()
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    private val listeners = CopyOnWriteArraySet<(Lease) -> Unit>()

    fun remember(mediaId: String, info: StreamInfo, nowMs: Long = System.currentTimeMillis()) {
        replace(mediaId, info, nowMs)
    }

    /** The single atomic source of truth read by the Media3 resolver. */
    fun replace(mediaId: String, info: StreamInfo, nowMs: Long = System.currentTimeMillis()): Lease? {
        if (mediaId.isBlank() || !valid(info, nowMs)) return null
        val uri = Uri.parse(info.url)
        val queryExpiry = uri.getQueryParameter("expire")?.toLongOrNull()?.let { value ->
            if (value < 10_000_000_000L) value * 1_000L else value
        }
        val resolvedAt = info.resolvedAtMs.takeIf { it > 0L } ?: nowMs
        val expiry = info.expiresAtEpochMs.takeIf { it > nowMs }
            ?: queryExpiry?.takeIf { it > nowMs }
            ?: (resolvedAt + UNKNOWN_EXPIRY_TTL_MS)
        val generation = generations.computeIfAbsent(mediaId) { AtomicLong() }.incrementAndGet()
        val normalized = info.copy(
            videoId = mediaId,
            itag = info.itag.takeIf { it > 0 } ?: uri.getQueryParameter("itag")?.toIntOrNull() ?: -1,
            mime = info.mime.ifBlank { uri.getQueryParameter("mime") ?: "" },
            contentLength = info.contentLength ?: uri.getQueryParameter("clen")?.toLongOrNull(),
            resolvedAtMs = resolvedAt,
            expiresAtEpochMs = expiry,
            generation = generation.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
        val lease = Lease(
            mediaId = mediaId,
            info = normalized,
            storedAtMs = nowMs,
            refreshAfterMs = (expiry - REFRESH_SAFETY_WINDOW_MS).coerceAtLeast(nowMs),
            generation = generation
        )
        leases[mediaId] = lease
        listeners.forEach { listener -> runCatching { listener(lease) } }
        return lease
    }

    fun current(mediaId: String, nowMs: Long = System.currentTimeMillis()): StreamInfo? {
        val lease = leases[mediaId] ?: return null
        // refreshAfterMs is a proactive-refresh deadline, not the URL's expiry.
        // Keep a still-valid URL usable while the background refresh runs.
        if (!valid(lease.info, nowMs)) {
            leases.remove(mediaId, lease)
            return null
        }
        return lease.info
    }

    fun lease(mediaId: String): Lease? = leases[mediaId]
    fun all(): List<Lease> = leases.values.toList()

    fun addListener(listener: (Lease) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (Lease) -> Unit) { listeners.remove(listener) }

    fun refreshDue(mediaId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        leases[mediaId]?.let { nowMs >= it.refreshAfterMs || !valid(it.info, nowMs) } ?: true

    fun invalidate(mediaId: String) {
        leases.remove(mediaId)
        generations.computeIfAbsent(mediaId) { AtomicLong() }.incrementAndGet()
    }
    fun trimTo(maxEntries: Int) {
        val excess = leases.size - maxEntries.coerceAtLeast(0)
        if (excess > 0) leases.values.sortedBy { it.storedAtMs }.take(excess).forEach { leases.remove(it.mediaId, it) }
    }

    private fun valid(info: StreamInfo, nowMs: Long): Boolean =
        (info.expiresAtEpochMs <= 0L || info.expiresAtEpochMs > nowMs) && runCatching {
            val uri = java.net.URI(info.url)
            uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
}
