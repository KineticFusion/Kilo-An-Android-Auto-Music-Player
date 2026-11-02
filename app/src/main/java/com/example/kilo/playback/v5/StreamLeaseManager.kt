package com.example.kilo.playback.v5

import com.example.kilo.streaming.StreamInfo
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ConcurrentHashMap

/** Tracks provider URL leases and marks expiring leases for refresh before reuse. */
object StreamLeaseManager {
    private const val REFRESH_SAFETY_WINDOW_MS = 45_000L

    data class Lease(
        val mediaId: String,
        val info: StreamInfo,
        val storedAtMs: Long,
        val refreshAfterMs: Long
    )

    private val leases = ConcurrentHashMap<String, Lease>()
    private val listeners = CopyOnWriteArraySet<(Lease) -> Unit>()

    fun remember(mediaId: String, info: StreamInfo, nowMs: Long = System.currentTimeMillis()) {
        if (mediaId.isBlank() || !valid(info, nowMs)) return
        val refreshAfter = if (info.expiresAtEpochMs > 0L) {
            (info.expiresAtEpochMs - REFRESH_SAFETY_WINDOW_MS).coerceAtLeast(nowMs)
        } else nowMs + 15 * 60_000L
        val lease = Lease(mediaId, info, nowMs, refreshAfter)
        leases[mediaId] = lease
        listeners.forEach { listener -> runCatching { listener(lease) } }
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

    fun invalidate(mediaId: String) { leases.remove(mediaId) }
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
