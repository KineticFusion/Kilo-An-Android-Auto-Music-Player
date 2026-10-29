package com.example.kilo.playback.prepared

import androidx.media3.common.MediaItem
import com.example.kilo.playbackoptimization.PreparedTrack as LegacyPreparedTrack
import com.example.kilo.streaming.StreamInfo

object PreparedTrackFactory {
    fun create(
        mediaItem: MediaItem,
        title: String,
        artist: String,
        artwork: String,
        durationMs: Long,
        streamInfo: StreamInfo? = null
    ): PreparedPlayableTrack {
        val now = System.currentTimeMillis()
        val validSource = streamInfo?.takeIf { info ->
            val uri = runCatching { java.net.URI(info.url) }.getOrNull()
            uri?.scheme?.lowercase() in setOf("http", "https") &&
                !uri?.host.isNullOrBlank() &&
                (info.expiresAtEpochMs <= 0L || info.expiresAtEpochMs > now)
        }
        val completeMetadata = mediaItem.mediaId.isNotBlank() &&
            title.isNotBlank() && artist.isNotBlank() &&
            mediaItem.localConfiguration?.uri != null
        return PreparedPlayableTrack(
            trackId = mediaItem.mediaId,
            title = title,
            artist = artist,
            artwork = artwork,
            durationMs = durationMs,
            mediaItem = mediaItem,
            streamInfo = validSource,
            preparedTimestampMs = now,
            expiryTimestampMs = validSource?.expiresAtEpochMs ?: 0L,
            healthStatus = when {
                validSource != null -> PreparedTrackHealth.VALID
                streamInfo != null -> PreparedTrackHealth.INVALID
                else -> PreparedTrackHealth.UNKNOWN
            },
            preparedState = when {
                validSource != null && completeMetadata -> PreparedTrackState.READY
                streamInfo != null -> PreparedTrackState.FAILED
                else -> PreparedTrackState.PREPARING
            }
        )
    }

    fun fromLegacy(track: LegacyPreparedTrack): PreparedPlayableTrack = PreparedPlayableTrack(
        trackId = track.mediaId,
        title = track.title,
        artist = track.artist,
        artwork = track.artworkUri,
        durationMs = track.durationMs,
        mediaItem = track.mediaItem,
        streamInfo = null,
        preparedTimestampMs = System.currentTimeMillis(),
        expiryTimestampMs = 0L,
        healthStatus = PreparedTrackHealth.UNKNOWN,
        preparedState = PreparedTrackState.PREPARING
    )

    fun fromMediaItem(item: MediaItem): PreparedPlayableTrack = PreparedPlayableTrack(
        trackId = item.mediaId,
        title = item.mediaMetadata.title?.toString().orEmpty(),
        artist = item.mediaMetadata.artist?.toString().orEmpty(),
        artwork = item.mediaMetadata.artworkUri?.toString().orEmpty(),
        durationMs = item.mediaMetadata.durationMs ?: 0L,
        mediaItem = item,
        streamInfo = null,
        preparedTimestampMs = System.currentTimeMillis(),
        expiryTimestampMs = 0L,
        healthStatus = PreparedTrackHealth.UNKNOWN,
        preparedState = PreparedTrackState.PREPARING
    )
}
