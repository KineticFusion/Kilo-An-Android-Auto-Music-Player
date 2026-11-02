package com.example.kilo.playback.v5

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.kilo.playbackrecovery.PlaybackDiagnostics
import com.example.kilo.playback.stability.PlaybackStabilityManager
import com.example.kilo.networkmonitor.NetworkHealthMonitor

/** Read-only playback state monitor. PlayerHolder remains the owner of recovery and Media3 lifecycle. */
object PlaybackStateMonitor : Player.Listener {
    @Volatile private var attachedPlayer: Player? = null
    @Volatile private var lastState = Player.STATE_IDLE
    @Volatile private var sessionStartedAtMs = 0L

    fun attach(player: Player) {
        if (attachedPlayer === player) return
        check(LooperAccess.isCurrent(player)) { "Playback session must attach on the player looper" }
        attachedPlayer?.removeListener(this)
        player.addListener(this)
        lastState = player.playbackState
        sessionStartedAtMs = System.currentTimeMillis()
        attachedPlayer = player
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId.orEmpty()
        PlaybackDiagnostics.record("session_track", id, "reason=$reason sessionAgeMs=${System.currentTimeMillis() - sessionStartedAtMs}")
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val snapshot = MainThreadPlayerGateway.snapshot()
        val previous = lastState
        lastState = playbackState
        if ((previous == Player.STATE_READY || previous == Player.STATE_BUFFERING) && playbackState == Player.STATE_IDLE) {
            val id = snapshot.mediaId.orEmpty()
            val health = PlaybackStabilityManager.latest(id)
            PlaybackDiagnostics.record(
                "playback_critical_incident", id,
                "transition=$previous->$playbackState pos=${snapshot.positionMs} buffered=${(snapshot.bufferedPositionMs - snapshot.positionMs).coerceAtLeast(0L)} " +
                    "playWhenReady=${snapshot.playWhenReady} sourceHealth=${health?.sourceState ?: "unknown"} " +
                    "network=${NetworkHealthMonitor.snapshot().transport}/${NetworkHealthMonitor.snapshot().grade} " +
                    "queue=${snapshot.currentIndex}/${snapshot.itemCount} owner=PlayerHolder"
            )
        }
    }

    private object LooperAccess {
        fun isCurrent(player: Player): Boolean = android.os.Looper.myLooper() == player.applicationLooper
    }
}
