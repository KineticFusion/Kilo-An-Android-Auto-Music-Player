package com.example.kilo.playbackrecovery

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.example.kilo.buffermanager.AdaptiveBufferManager
import com.example.kilo.networkmonitor.NetworkHealthMonitor

/**
 * Observes stalls and the existing player's recovery result. It intentionally never calls
 * play, pause, prepare, seek, or changes a media item; the current player owns recovery.
 */
class PlaybackRecoveryObserver(
    private val existingRecoveryActive: (String) -> Boolean
) : Player.Listener {
    private var player: Player? = null
    private var startedTrackId: String? = null
    private var bufferingTrackId: String? = null
    private var bufferingStartedAtMs = 0L

    fun attachTo(player: Player) { this.player = player }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        finishBuffering("track_transition")
        startedTrackId = mediaItem?.mediaId?.takeIf { player?.isPlaying == true }
        PlaybackDiagnostics.record(
            "track_transition", mediaItem?.mediaId.orEmpty(), "reason=$reason"
        )
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val activePlayer = player ?: return
        val id = activePlayer.currentMediaItem?.mediaId ?: return
        if (isPlaying) {
            startedTrackId = id
            finishBuffering("playback_resumed")
            PlaybackDiagnostics.record("playing", id, "positionMs=${activePlayer.currentPosition}")
        } else if (activePlayer.playWhenReady && activePlayer.playbackState == Player.STATE_BUFFERING) {
            beginBuffering(id)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val activePlayer = player ?: return
        val id = activePlayer.currentMediaItem?.mediaId ?: return
        if (playbackState == Player.STATE_BUFFERING && activePlayer.playWhenReady && startedTrackId == id) {
            beginBuffering(id)
        } else if (playbackState == Player.STATE_READY) {
            finishBuffering("ready")
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val id = player?.currentMediaItem?.mediaId.orEmpty()
        val retrying = id.isNotBlank() && runCatching { existingRecoveryActive(id) }.getOrDefault(false)
        PlaybackDiagnostics.record(
            "player_error", id,
            "code=${error.errorCode} name=${error.errorCodeName} existingRecoveryActive=$retrying " +
                "network=${NetworkHealthMonitor.snapshot().grade}"
        )
        if (retrying) PlaybackDiagnostics.record("existing_retry_active", id, "recovery remains owned by current player")
    }

    private fun beginBuffering(mediaId: String) {
        if (bufferingTrackId == mediaId) return
        finishBuffering("superseded")
        bufferingTrackId = mediaId
        bufferingStartedAtMs = SystemClock.elapsedRealtime()
        PlaybackDiagnostics.record(
            "buffer_start", mediaId,
            "network=${NetworkHealthMonitor.snapshot().grade} bufferedAheadMs=${player?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0L) } ?: -1L}"
        )
    }

    private fun finishBuffering(reason: String) {
        val id = bufferingTrackId ?: return
        val duration = (SystemClock.elapsedRealtime() - bufferingStartedAtMs).coerceAtLeast(0L)
        bufferingTrackId = null
        bufferingStartedAtMs = 0L
        if (duration >= 1_000L) AdaptiveBufferManager.recordUnderrun()
        PlaybackDiagnostics.record("buffer_recovered", id, "durationMs=$duration reason=$reason")
        if (duration >= 2_000L) PlaybackDiagnostics.record("buffer_stall", id, "durationMs=$duration")
    }
}
