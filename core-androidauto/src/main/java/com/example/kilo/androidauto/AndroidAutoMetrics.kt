package com.example.kilo.androidauto

import android.os.Debug
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/** Lightweight, sampled diagnostics for browse/cache work; never logs a line per row. */
object AndroidAutoMetrics {
    private const val TAG = "KiloAutoMetrics"
    private const val REPORT_EVERY = 50L
    private val lookups = AtomicLong()
    private val hits = AtomicLong()
    private val databaseReads = AtomicLong()
    private val artworkLoads = AtomicLong()

    fun recordCacheLookup(parentId: String, hit: Boolean, itemCount: Int) {
        lookups.incrementAndGet()
        if (hit) hits.incrementAndGet()
        if (!hit && lookups.get() % REPORT_EVERY == 0L) {
            Log.d(TAG, "browse_cache lookups=${lookups.get()} hits=${hits.get()} parent=$parentId items=$itemCount")
        }
    }

    fun recordDatabaseRead(durationMs: Long, itemCount: Int, succeeded: Boolean) {
        databaseReads.incrementAndGet()
        if (durationMs >= 100L || databaseReads.get() % REPORT_EVERY == 0L) {
            Log.d(TAG, "database_read durationMs=$durationMs items=$itemCount success=$succeeded")
        }
    }

    fun recordArtworkLoad(key: String, durationMs: Long, succeeded: Boolean) {
        artworkLoads.incrementAndGet()
        if (durationMs >= 100L || artworkLoads.get() % REPORT_EVERY == 0L) {
            Log.d(TAG, "artwork key=$key durationMs=$durationMs success=$succeeded")
        }
    }

    fun logMemorySnapshot() {
        val usedHeapKb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024L
        Log.i(TAG, "memory usedHeapKb=$usedHeapKb nativePssKb=${Debug.getNativeHeapAllocatedSize() / 1024L} cachedItems=${AndroidAutoCache.cachedItemCount()}")
    }
}
