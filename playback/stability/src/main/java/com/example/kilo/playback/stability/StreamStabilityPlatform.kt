package com.example.kilo.playback.stability

import androidx.media3.common.Player
import android.os.SystemClock
import com.example.kilo.observability.PlaybackProfiler
import com.example.kilo.playbackoptimization.PlaybackHealthMonitor
import com.example.kilo.playbackoptimization.SourceLifecycleState
import com.example.kilo.playbackoptimization.SourceLifecycleManager
import com.example.kilo.playbackoptimization.SourceHealthValidator
import com.example.kilo.playbackoptimization.SourceFailureTracker
import com.example.kilo.playbackoptimization.WarmedSourceMetadata
import com.example.kilo.playback.source.SourceRefreshEngine
import java.util.LinkedHashMap

object StreamGuard {
    fun validate(mediaId: String, source: WarmedSourceMetadata): Boolean {
        val valid = SourceHealthValidator.isUsable(source)
        if (!valid) SourceLifecycleManager.transition(mediaId, SourceLifecycleState.EXPIRING)
        return valid
    }
}

/** Records the buffer safety window only; ExoPlayer buffer configuration remains untouched. */
object BufferGuard {
    fun hasSafetyWindow(bufferedMs: Long, androidAuto: Boolean): Boolean =
        bufferedMs >= if (androidAuto) 40_000L else 25_000L

    fun observe(bufferedMs: Long, buffering: Boolean, androidAuto: Boolean) {
        if (!hasSafetyWindow(bufferedMs, androidAuto)) {
            PlaybackProfiler.record(
                "buffer_safety_window_low",
                detail = "bufferedMs=${bufferedMs.coerceAtLeast(0)} androidAuto=$androidAuto buffering=$buffering"
            )
        }
    }
}

object ContinuityManager {
    fun observe(player: Player) {
        val id = player.currentMediaItem?.mediaId ?: return
        val bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
        val state = when (player.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN"
        }
        PlaybackProfiler.playerState(id, state, player.playWhenReady, player.isPlaying, bufferedMs)
    }
}

/** Read-only tripwire for source/timeline changes observed while playback is active. */
object MediaSourceProtectionLayer {
    fun observeTimelineChange(player: Player, reason: Int) {
        if (!player.playWhenReady && !player.isPlaying) return
        val mediaId = player.currentMediaItem?.mediaId.orEmpty()
        MediaSourceAudit.observed(
            "timeline_changed",
            mediaId,
            player.currentPosition,
            player.playbackState,
            "reason=$reason queueIndex=${player.currentMediaItemIndex} " +
                "queueCount=${player.mediaItemCount} thread=${Thread.currentThread().name} " +
                "interception=read_only_listener"
        )
    }
}

/** Simple recovery guard used by wrapper refresh paths. */
object IdleGuard {
    fun shouldBlockRecovery(
        playWhenReady: Boolean,
        bufferedMs: Long,
        sourceValid: Boolean,
        positionAdvancing: Boolean = false
    ): Boolean = playWhenReady && positionAdvancing && bufferedMs > 5_000L && sourceValid
}

/** Deduplicates wrapper-owned source refreshes and keeps work off active playback calls. */
object SafeSourceRefreshManager {
    private const val MAX_TRACKS = 64
    private const val REFRESH_COOLDOWN_MS = 30_000L
    private val lock = Any()
    private val refreshedAt = object : LinkedHashMap<String, Long>(MAX_TRACKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_TRACKS
    }

    fun refresh(
        mediaId: String,
        reason: String,
        playWhenReady: Boolean,
        bufferedMs: Long,
        sourceValid: Boolean,
        proactive: Boolean,
        positionAdvancing: Boolean = false
    ): Boolean {
        if (mediaId.isBlank()) return false
        if (!proactive && IdleGuard.shouldBlockRecovery(playWhenReady, bufferedMs, sourceValid, positionAdvancing)) {
            PlaybackProfiler.record(
                "idle_guard_blocked_recovery",
                mediaId,
                "reason=$reason bufferedMs=${bufferedMs.coerceAtLeast(0L)} sourceValid=$sourceValid"
            )
            return false
        }
        val now = SystemClock.elapsedRealtime()
        val refreshKey = "$mediaId:${if (proactive) "preload" else "recovery"}"
        synchronized(lock) {
            val previous = refreshedAt[refreshKey]
            if (previous != null && now - previous < REFRESH_COOLDOWN_MS) {
                PlaybackProfiler.record("source_refresh_duplicate_suppressed", mediaId, "reason=$reason")
                return false
            }
            refreshedAt[refreshKey] = now
        }
        PlaybackProfiler.record(
            if (proactive) "source_refresh_background" else "source_refresh_recovery",
            mediaId,
            "reason=$reason"
        )
        SourceRefreshEngine.refresh(mediaId)
        return true
    }
}

object HealthMonitor {
    fun listener(): Player.Listener = PlaybackHealthMonitor.createListener()
}
