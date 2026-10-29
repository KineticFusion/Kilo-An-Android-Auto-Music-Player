package com.example.kilo.playback.stability

enum class IdleRootCause {
    SOURCE_EXPIRED,
    SOURCE_REPLACED,
    CODEC_TERMINATED,
    PLAYER_RESET,
    MEDIA_SOURCE_REBUILT,
    UNKNOWN_IDLE,
    TRACK_OR_QUEUE_CHANGED,
    USER_PAUSED_OR_STOPPED
}

/** Classifies an observed transition from collected evidence; it never initiates recovery. */
object IdleRootCauseAnalyzer {
    fun classify(previous: PlaybackHealthSnapshot?, current: PlaybackHealthSnapshot): IdleRootCause {
        if (previous == null || previous.mediaId != current.mediaId) return IdleRootCause.UNKNOWN_IDLE
        if (previous.queueIndex != current.queueIndex) return IdleRootCause.TRACK_OR_QUEUE_CHANGED
        if (!current.playWhenReady) return IdleRootCause.USER_PAUSED_OR_STOPPED
        if (current.decoderFaulted) return IdleRootCause.CODEC_TERMINATED
        if (!current.sourceValid || current.sourceState in setOf("EXPIRING", "FAILED", "INVALID")) return IdleRootCause.SOURCE_EXPIRED
        if (previous.activeMediaSource.isNotBlank() && current.activeMediaSource.isNotBlank() &&
            previous.activeMediaSource != current.activeMediaSource
        ) return IdleRootCause.SOURCE_REPLACED
        if (current.timelineRevision != previous.timelineRevision) return IdleRootCause.MEDIA_SOURCE_REBUILT
        if ((current.timelineWindows == 0 && previous.timelineWindows > 0) ||
            current.positionMs + 500L < previous.positionMs
        ) return IdleRootCause.PLAYER_RESET
        return IdleRootCause.UNKNOWN_IDLE
    }
}
