package com.example.kilo.androidauto

import android.util.LruCache
import android.os.SystemClock
import androidx.media3.common.MediaItem

/** Bounded in-memory browse cache. Playback items and their metadata remain unchanged. */
object AndroidAutoCache {
    private const val MAX_CACHE_WEIGHT = 4 * 1024 * 1024
    private const val ITEM_METADATA_WEIGHT = 1024L
    private const val ENTRY_TTL_MS = 10 * 60 * 1000L

    private data class CachedChildren(val items: List<MediaItem>, val savedAtMs: Long)

    private val children = object : LruCache<String, CachedChildren>(MAX_CACHE_WEIGHT) {
        override fun sizeOf(key: String, value: CachedChildren): Int = value.items.sumOf { item ->
            ITEM_METADATA_WEIGHT + (item.mediaMetadata.artworkData?.size?.toLong() ?: 0L)
        }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    }

    @Synchronized
    fun get(parentId: String): List<MediaItem> {
        val entry = children.get(parentId)
        val fresh = entry?.takeIf { SystemClock.elapsedRealtime() - it.savedAtMs <= ENTRY_TTL_MS }
        if (entry != null && fresh == null) children.remove(parentId)
        val items = fresh?.items.orEmpty()
        AndroidAutoMetrics.recordCacheLookup(parentId, hit = fresh != null, itemCount = items.size)
        return items
    }

    @Synchronized
    fun put(parentId: String, items: List<MediaItem>) {
        if (parentId.isBlank() || items.isEmpty()) return
        // Keep normal Android Auto pages, but avoid pinning very large catalog results in RAM.
        if (items.size > 240) return
        children.put(parentId, CachedChildren(items.toList(), SystemClock.elapsedRealtime()))
    }

    @Synchronized
    fun invalidate(parentId: String) {
        children.remove(parentId)
    }

    @Synchronized
    fun trim() {
        children.trimToSize(MAX_CACHE_WEIGHT / 2)
    }

    @Synchronized
    fun clear() {
        children.evictAll()
    }

    fun cachedItemCount(): Int = children.snapshot().values.sumOf { it.items.size }
}
