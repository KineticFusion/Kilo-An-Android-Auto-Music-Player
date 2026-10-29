package com.example.kilo.playback.cache

import androidx.media3.common.Player
import com.example.kilo.playbackoptimization.ArtworkWarmupManager
import com.example.kilo.playbackoptimization.MetadataCacheManager
import com.example.kilo.playbackoptimization.PreparedTrack
import com.example.kilo.playbackoptimization.PreparedTrackRecord
import com.example.kilo.playbackoptimization.PreparedTrackStore
import com.example.kilo.playbackoptimization.StreamPreparationPool
import com.example.kilo.playbackoptimization.WarmedSourceMetadata
import java.util.LinkedHashMap

object MemorySourceCache {
    fun get(mediaId: String): WarmedSourceMetadata? = StreamPreparationPool.get(mediaId)
    fun put(mediaId: String, source: WarmedSourceMetadata) = StreamPreparationPool.put(mediaId, source)
    fun clear(mediaId: String) = StreamPreparationPool.clear(mediaId)
}

object SessionSourceCache {
    fun prepared(mediaId: String): PreparedTrackRecord? = PreparedTrackStore.get(mediaId)
}

object ArtworkCache {
    fun warm(context: android.content.Context, uri: String, maxPixels: Int = 512) =
        ArtworkWarmupManager.warm(context.applicationContext, uri, maxPixels)
}

object MetadataCache {
    fun put(track: PreparedTrack) = MetadataCacheManager.put(track)
    fun get(mediaId: String): PreparedTrack? = MetadataCacheManager.get(mediaId)
}

data class QueueSnapshot(val mediaIds: List<String>, val currentIndex: Int, val positionMs: Long, val capturedAtMs: Long)

object QueueSnapshotCache {
    private const val MAX_ITEMS = 6
    @Volatile private var latest: QueueSnapshot? = null

    fun capture(player: Player): QueueSnapshot {
        val count = player.mediaItemCount.coerceAtLeast(0)
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        val ids = (0 until minOf(count, MAX_ITEMS)).mapNotNull { index ->
            runCatching { player.getMediaItemAt(index).mediaId }.getOrNull()?.takeIf(String::isNotBlank)
        }
        return QueueSnapshot(ids, current.coerceAtMost((ids.size - 1).coerceAtLeast(0)), player.currentPosition.coerceAtLeast(0L), System.currentTimeMillis())
            .also { latest = it }
    }

    fun latest(): QueueSnapshot? = latest
}

/** Signed stream URLs remain memory-only; persistent audio bytes stay in KILO's existing audio cache. */
object DiskSourceCache {
    private const val MAX_TRACKS = 128
    private val bytesByTrack = object : LinkedHashMap<String, Long>(MAX_TRACKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_TRACKS
    }

    /** Mirrors coverage reported by KILO's existing disk audio cache; URLs are never written here. */
    fun recordAudioCoverage(mediaId: String, cachedBytes: Long) = synchronized(bytesByTrack) {
        if (mediaId.isNotBlank()) bytesByTrack[mediaId] = cachedBytes.coerceAtLeast(0L)
    }

    fun cachedAudioBytes(mediaId: String): Long = synchronized(bytesByTrack) { bytesByTrack[mediaId] ?: 0L }
}
