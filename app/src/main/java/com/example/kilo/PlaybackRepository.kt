package com.example.kilo

import androidx.media3.common.MediaItem
import com.example.kilo.streaming.StreamInfo
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local repository for tracks KILO has already loaded.
 *
 * It keeps lightweight track metadata and reusable MediaItems in memory. Resolved stream
 * URLs are also retained only in memory because provider URLs can expire. Audio bytes stay
 * in the existing Media3 AudioCache; this repository never downloads whole songs.
 */
object PlaybackRepository {
    private const val MAX_TRACKS = 512
    private const val MAX_STREAMS = 256
    private const val TRACK_TTL_MS = 30 * 60 * 1000L
    private const val STREAM_TTL_MS = 15 * 60 * 1000L
    private val streamHits = AtomicLong()
    private val streamMisses = AtomicLong()

    private data class TrackEntry(
        val song: SongItemClean,
        val fingerprint: String,
        val mediaItem: MediaItem?,
        val savedAtMs: Long
    )

    private data class StreamEntry(val info: StreamInfo, val savedAtMs: Long)

    private val tracks = object : LinkedHashMap<String, TrackEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackEntry>?): Boolean =
            size > MAX_TRACKS
    }
    private val streams = object : LinkedHashMap<String, StreamEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamEntry>?): Boolean =
            size > MAX_STREAMS
    }

    fun trackKey(song: SongItemClean): String = song.videoId.ifBlank { song.id }.trim()

    fun remember(song: SongItemClean): SongItemClean {
        val key = trackKey(song)
        if (key.isBlank()) return song
        val fingerprint = fingerprint(song)
        return synchronized(tracks) {
            val old = tracks[key]
            if (old != null && old.fingerprint == fingerprint &&
                System.currentTimeMillis() - old.savedAtMs <= TRACK_TTL_MS
            ) {
                tracks[key] = old.copy(savedAtMs = System.currentTimeMillis())
                return@synchronized old.song
            }
            tracks[key] = TrackEntry(
                song = song,
                fingerprint = fingerprint,
                mediaItem = old?.mediaItem?.takeIf { old.fingerprint == fingerprint },
                savedAtMs = System.currentTimeMillis()
            )
            song
        }
    }

    fun rememberAll(songs: Iterable<SongItemClean>) {
        songs.forEach(::remember)
    }

    fun song(mediaId: String): SongItemClean? = synchronized(tracks) {
        val entry = tracks[mediaId] ?: return@synchronized null
        if (System.currentTimeMillis() - entry.savedAtMs > TRACK_TTL_MS) {
            tracks.remove(mediaId)
            null
        } else entry.song
    }

    fun mediaItem(mediaId: String, fingerprint: String): MediaItem? = synchronized(tracks) {
        val entry = tracks[mediaId] ?: return@synchronized null
        if (System.currentTimeMillis() - entry.savedAtMs > TRACK_TTL_MS || entry.fingerprint != fingerprint) {
            if (System.currentTimeMillis() - entry.savedAtMs > TRACK_TTL_MS) tracks.remove(mediaId)
            null
        } else entry.mediaItem
    }

    fun rememberMediaItem(mediaId: String, fingerprint: String, item: MediaItem) {
        synchronized(tracks) {
            val old = tracks[mediaId] ?: return
            if (old.fingerprint == fingerprint) {
                tracks[mediaId] = old.copy(mediaItem = item, savedAtMs = System.currentTimeMillis())
            }
        }
    }

    fun rememberResolvedStream(mediaId: String, info: StreamInfo) {
        if (mediaId.isBlank() || !isValid(info)) return
        synchronized(streams) {
            streams[mediaId] = StreamEntry(info, System.currentTimeMillis())
        }
    }

    fun resolvedStream(mediaId: String): StreamInfo? = synchronized(streams) {
        val entry = streams[mediaId] ?: run {
            streamMisses.incrementAndGet()
            return@synchronized null
        }
        val expiredByAge = System.currentTimeMillis() - entry.savedAtMs > STREAM_TTL_MS
        if (expiredByAge || !isValid(entry.info)) {
            streams.remove(mediaId)
            streamMisses.incrementAndGet()
            null
        } else entry.info.also { streamHits.incrementAndGet() }
    }

    fun streamCacheMetrics(): Map<String, Long> = mapOf(
        "hits" to streamHits.get(),
        "misses" to streamMisses.get(),
        "entries" to synchronized(streams) { streams.size.toLong() },
        "ttlMs" to STREAM_TTL_MS
    )

    fun cacheEntryCounts(): Map<String, Int> = mapOf(
        "tracks" to synchronized(tracks) { tracks.size },
        "streams" to synchronized(streams) { streams.size }
    )

    fun invalidateResolvedStream(mediaId: String) {
        synchronized(streams) { streams.remove(mediaId) }
    }

    private fun isValid(info: StreamInfo): Boolean =
        (info.expiresAtEpochMs <= 0L || info.expiresAtEpochMs > System.currentTimeMillis()) &&
            runCatching {
                val uri = java.net.URI(info.url)
                (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
            }.getOrDefault(false)

    private fun fingerprint(song: SongItemClean): String = listOf(
        song.title,
        song.artist,
        song.album,
        song.durationMs.toString(),
        song.coverUrl
    ).joinToString("\u001f")
}
