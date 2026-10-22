package com.example.kilo.streaming

import java.util.concurrent.ConcurrentHashMap

/** Short lived cache for signed stream URLs; expired URLs are never returned. */
class ResolvedStreamCache(private val maxEntries: Int = 512) {
    private data class Entry(val info: StreamInfo, val expiresAt: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(mediaId: String, nowMs: Long = System.currentTimeMillis()): StreamInfo? {
        val entry = entries[mediaId] ?: return null
        if (entry.expiresAt <= nowMs) {
            entries.remove(mediaId, entry)
            return null
        }
        return entry.info
    }

    fun put(mediaId: String, info: StreamInfo, expiresAtMs: Long) {
        if (expiresAtMs <= System.currentTimeMillis()) return
        entries[mediaId] = Entry(info.copy(expiresAtEpochMs = expiresAtMs), expiresAtMs)
        if (entries.size > maxEntries) {
            val oldest = entries.entries.minByOrNull { it.value.expiresAt }
            if (oldest != null) entries.remove(oldest.key, oldest.value)
        }
    }

    fun invalidate(mediaId: String) {
        entries.remove(mediaId)
    }

    fun clear() {
        entries.clear()
    }
}
