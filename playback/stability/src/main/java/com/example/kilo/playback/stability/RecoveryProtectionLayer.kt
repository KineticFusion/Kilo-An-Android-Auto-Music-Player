package com.example.kilo.playback.stability

import androidx.media3.common.Player
import android.os.SystemClock
import com.example.kilo.observability.PlaybackProfiler

data class RecoveryProtectionDecision(
    val shouldBlock: Boolean,
    val reason: String,
    val latestHealthyPositionMs: Long = -1L,
    val latestHealthyBufferedMs: Long = -1L
)

/** Blocks wrapper recovery only when recent samples prove the same source was healthy and advancing. */
object RecoveryProtectionLayer {
    fun isHealthyProgress(previous: PlaybackHealthSnapshot?, current: PlaybackHealthSnapshot): Boolean =
        previous != null && previous.mediaId == current.mediaId &&
            current.playWhenReady && current.sourceValid && current.bufferedMs > 5_000L &&
            current.positionMs > previous.positionMs

    fun shouldBlockRecovery(previous: PlaybackHealthSnapshot?, current: PlaybackHealthSnapshot): Boolean =
        isHealthyProgress(previous, current)

    /**
     * The service calls this before rebuilding after an unexpected IDLE. It uses two recent
     * pre-IDLE samples because the player itself cannot advance once it has entered IDLE.
     */
    fun unexpectedIdleDecision(mediaId: String, nowElapsedMs: Long = SystemClock.elapsedRealtime()): RecoveryProtectionDecision {
        val progress = PlaybackWatchdog.recentHealthyProgress(mediaId, nowElapsedMs)
            ?: return RecoveryProtectionDecision(false, "no_recent_healthy_progress")
        val (previous, current) = progress
        val decision = RecoveryProtectionDecision(
            shouldBlock = true,
            reason = "recent_ready_playing_source_valid_buffer_gt_5s_and_position_advanced",
            latestHealthyPositionMs = current.positionMs,
            latestHealthyBufferedMs = current.bufferedMs
        )
        PlaybackProfiler.record(
            "idle_recovery_blocked",
            mediaId,
            "reason=${decision.reason} fromPositionMs=${previous.positionMs} " +
                "toPositionMs=${current.positionMs} bufferedMs=${current.bufferedMs} " +
                "sampleAgeMs=${(nowElapsedMs - current.elapsedRealtimeMs).coerceAtLeast(0L)}"
        )
        return decision
    }
}
