package com.example.kilo.androidauto

import android.util.LruCache
import androidx.media3.common.MediaItem

/** Bounded in-memory browse cache. Playback items and their metadata remain unchanged. */
object AndroidAutoCache {
    private const val MAX_CACHE_WEIGHT = 4 * 1024 * 1024
    private const val ITEM_METADATA_WEIGHT = 1024L

    private val children = object : LruCache<String, List<MediaItem>>(MAX_CACHE_WEIGHT) {
        override fun sizeOf(key: String, value: List<MediaItem>): Int = value.sumOf { item ->
            ITEM_METADATA_WEIGHT + (item.mediaMetadata.artworkData?.size?.toLong() ?: 0L)
        }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    }

    @Synchronized
    fun get(parentId: String): List<MediaItem> {
        val value = children.get(parentId)
        AndroidAutoMetrics.recordCacheLookup(parentId, hit = value != null, itemCount = value?.size ?: 0)
        return value.orEmpty()
    }

    @Synchronized
    fun put(parentId: String, items: List<MediaItem>) {
        if (parentId.isBlank() || items.isEmpty()) return
        // Keep normal Android Auto pages, but avoid pinning very large catalog results in RAM.
        if (items.size > 240) return
        children.put(parentId, items.toList())
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

    fun cachedItemCount(): Int = children.snapshot().values.sumOf { it.size }
}
