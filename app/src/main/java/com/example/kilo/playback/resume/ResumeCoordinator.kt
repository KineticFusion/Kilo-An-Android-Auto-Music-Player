package com.example.kilo.playback.resume

import android.content.Context
import androidx.media3.common.Player
import com.example.kilo.SessionStore

/** Restores the saved queue paused, leaving playback start under the user's control. */
object ResumeCoordinator {
    fun restoreQueueIfNeeded(context: Context, player: Player): Boolean {
        if (player.mediaItemCount > 0) return false
        val restored = SessionStore.load() ?: return false
        val snapshot = LastSessionRepository(context).load()
        player.repeatMode = snapshot?.repeatMode ?: player.repeatMode
        player.shuffleModeEnabled = snapshot?.shuffleEnabled ?: player.shuffleModeEnabled
        player.setMediaItems(restored.items, restored.index, restored.posMs.coerceAtLeast(0L))
        BackgroundResumeLog.restored(restored.items.size, restored.index, restored.posMs, snapshot?.playWhenReady == true)
        // Deliberately do not auto-play on a normal app launch; media controllers can
        // resume through the existing MediaLibrarySession callback when requested.
        return true
    }

    fun applyModes(context: Context, player: Player) {
        val snapshot = LastSessionRepository(context).load() ?: return
        player.repeatMode = snapshot.repeatMode
        player.shuffleModeEnabled = snapshot.shuffleEnabled
    }
}

private object BackgroundResumeLog {
    fun restored(count: Int, index: Int, positionMs: Long, previousIntent: Boolean) {
        com.example.kilo.playback.background.BackgroundPlaybackMetrics.event(
            "queue_restored", "count=$count index=$index positionMs=$positionMs priorPlayIntent=$previousIntent"
        )
    }
}
