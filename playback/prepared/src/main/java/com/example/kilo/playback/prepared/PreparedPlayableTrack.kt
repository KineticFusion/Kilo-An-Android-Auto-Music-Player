package com.example.kilo.playback.prepared

import androidx.media3.common.MediaItem
import com.example.kilo.streaming.StreamInfo

enum class PreparedTrackHealth { UNKNOWN, VALID, EXPIRING, INVALID }
enum class PreparedTrackState { PREPARING, READY, PLAYING, EXPIRING, REFRESHING, FAILED }

/** One consistent snapshot of track metadata and the source preparation state. */
data class PreparedPlayableTrack(
    val trackId: String,
    val title: String,
    val artist: String,
    val artwork: String,
    val durationMs: Long,
    val mediaItem: MediaItem?,
    val streamInfo: StreamInfo?,
    val preparedTimestampMs: Long,
    val expiryTimestampMs: Long,
    val healthStatus: PreparedTrackHealth,
    val preparedState: PreparedTrackState
) {
    fun isReady(nowMs: Long = System.currentTimeMillis()): Boolean =
        preparedState in setOf(PreparedTrackState.READY, PreparedTrackState.PLAYING) &&
            healthStatus == PreparedTrackHealth.VALID &&
            mediaItem != null && streamInfo != null &&
            trackId.isNotBlank() && title.isNotBlank() && artist.isNotBlank() &&
            mediaItem.mediaId == trackId && mediaItem.localConfiguration?.uri != null &&
            streamInfo.url.isNotBlank() &&
            (expiryTimestampMs <= 0L || expiryTimestampMs > nowMs)
}
