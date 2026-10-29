package com.example.kilo.playback.prepared

import com.example.kilo.playbackoptimization.PreparedTrack as PreparedTrackModel
import com.example.kilo.playbackoptimization.PreparedTrackFactory as LegacyPreparedTrackFactory
import com.example.kilo.playbackoptimization.PreparedTrackRecord
import com.example.kilo.playbackoptimization.PreparedTrackRegistry as LegacyPreparedTrackRegistry
import com.example.kilo.playbackoptimization.PreparedTrackStatus
import com.example.kilo.playbackoptimization.PreparedTrackStore as LegacyPreparedTrackStore
import com.example.kilo.playbackoptimization.SourceWarmupResolver
import androidx.media3.common.MediaItem

typealias PreparedTrack = PreparedTrackModel
typealias PreparedTrackStore = LegacyPreparedTrackStore
typealias PreparedTrackCleaner = com.example.kilo.playbackoptimization.PreparedTrackCleaner
typealias PreparedTrackScheduler = com.example.kilo.playbackoptimization.PreparedTrackScheduler

/** Public module boundary for prepared playback; delegates storage to the bounded legacy adapter. */
object PreparedPlaybackPlatform {
    fun register(track: PreparedTrack) = PreparedTrackRegistry.register(PreparedTrackFactory.fromLegacy(track))
    fun get(trackId: String): PreparedTrackRecord? = LegacyPreparedTrackStore.get(trackId)
    fun snapshot(): List<PreparedTrackRecord> = LegacyPreparedTrackStore.snapshot()
    fun schedule(trackId: String, resolver: SourceWarmupResolver, retry: Boolean = false) =
        com.example.kilo.playbackoptimization.PreparedTrackScheduler.schedule(trackId, resolver, retry)
    fun markPlaying(trackId: String) = LegacyPreparedTrackRegistry.markPlaying(trackId)
    fun cleanExpired() = com.example.kilo.playbackoptimization.PreparedTrackCleaner.clean()
    fun hasReadySource(trackId: String): Boolean = PreparedPlaybackLayer.isPlaybackReady(trackId)

    /** Returns the already-built, source-ready MediaItem when its metadata is still valid. */
    fun preparedMediaItem(trackId: String): MediaItem? {
        val item = PreparedPlaybackLayer.readyMediaItem(trackId) ?: return null
        if (item.mediaId != trackId || item.localConfiguration?.uri == null) return null
        if (item.mediaMetadata.title?.toString().isNullOrBlank()) return null
        return item
    }
    fun create(
        item: MediaItem,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        artworkUri: String
    ): PreparedTrack = LegacyPreparedTrackFactory.create(item, title, artist, album, durationMs, artworkUri).also(::register)
}

/** Bridges a click to the prepared-store lookup without issuing any player command. */
object ClickToPlayCoordinator {
    fun isPrepared(trackId: String): Boolean = PreparedPlaybackPlatform.hasReadySource(trackId)

    /** Reuses a warmed item's MediaItem; the caller's existing path remains the fallback. */
    fun mediaItemOrNull(trackId: String): MediaItem? = PreparedPlaybackPlatform.preparedMediaItem(trackId)
}
