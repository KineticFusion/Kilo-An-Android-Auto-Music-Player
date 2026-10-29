package com.example.kilo.playback.stability

/** Immutable point-in-time view of the shared player and its active source. */
data class PlaybackHealthSnapshot(
    val mediaId: String,
    val elapsedRealtimeMs: Long,
    val positionMs: Long,
    val bufferedMs: Long,
    val playerState: Int,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val sourceValid: Boolean,
    val sourceState: String,
    val recoveryState: String,
    val queueIndex: Int,
    val networkState: String,
    val bandwidthKbps: Long = 0L,
    val timelineWindows: Int = 0,
    val timelinePeriods: Int = 0,
    val timelineRevision: Long = 0L,
    val activeMediaSource: String = "",
    val decoderName: String = "",
    val decoderFaulted: Boolean = false
) {
    val bufferedDurationMs: Long get() = bufferedMs
    val timestampMs: Long get() = elapsedRealtimeMs
}
