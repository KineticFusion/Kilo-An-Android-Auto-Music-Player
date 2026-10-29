package com.example.kilo.playback.prepared

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.kilo.playbackoptimization.PreparedTrack as LegacyPreparedTrack

/** Presentation-safe readiness facade over the existing resolver and player path. */
object PreparedPlaybackLayer {
    fun prepareVisible(
        context: Context,
        tracks: List<LegacyPreparedTrack>,
        section: String,
        limit: Int
    ) = PreparationOrchestrator.prepareVisible(context.applicationContext, tracks, section, limit)

    fun get(trackId: String): PreparedTrackPoolEntry? {
        if (trackId.isBlank()) return null
        val registered = PreparedTrackRegistry.get(trackId)
        if (registered == null) return PreparedTrackPool.get(trackId)

        val readiness = when {
            registered.preparedState == PreparedTrackState.FAILED -> PreparedReadinessState.FAILED
            registered.isReady() -> PreparedReadinessState.PLAYBACK_READY
            registered.streamInfo != null -> PreparedReadinessState.SOURCE_READY
            registered.preparedState == PreparedTrackState.PREPARING -> PreparedReadinessState.PREPARING
            else -> PreparedReadinessState.DISCOVERED
        }
        return PreparedTrackPool.put(registered, readiness)
    }

    /** Existing Media3 queue construction remains the handoff; this avoids repeating track metadata work. */
    fun readyMediaItem(trackId: String): MediaItem? = get(trackId)
        ?.takeIf { it.readiness == PreparedReadinessState.PLAYBACK_READY }
        ?.track?.mediaItem

    fun isPlaybackReady(trackId: String): Boolean =
        get(trackId)?.readiness == PreparedReadinessState.PLAYBACK_READY

    /** Mirrors the current queue and five following items into the memory-only readiness pool. */
    fun observeQueue(player: Player) {
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        if (count == 0 || current !in 0 until count) return
        val wrap = player.repeatMode == Player.REPEAT_MODE_ALL
        val seen = HashSet<String>(6)
        for (offset in 0..5) {
            var index = current + offset
            if (index >= count) {
                if (!wrap) break
                index %= count
            }
            val item = runCatching { player.getMediaItemAt(index) }.getOrNull() ?: continue
            val id = item.mediaId
            if (id.isBlank() || id.startsWith("radio:") || !seen.add(id)) continue
            val track = PreparedTrackRegistry.get(id) ?: PreparedTrackFactory.fromMediaItem(item)
            val readiness = when {
                track.preparedState == PreparedTrackState.FAILED -> PreparedReadinessState.FAILED
                track.isReady() -> PreparedReadinessState.PLAYBACK_READY
                track.streamInfo != null -> PreparedReadinessState.SOURCE_READY
                track.preparedState == PreparedTrackState.PREPARING -> PreparedReadinessState.PREPARING
                else -> PreparedReadinessState.DISCOVERED
            }
            PreparedTrackPool.put(track, readiness)
        }
    }
}
