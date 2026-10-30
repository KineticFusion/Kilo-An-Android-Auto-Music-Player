package com.example.kilo.playback.resume

import android.content.Context
import android.util.Log
import androidx.media3.common.Player
import com.example.kilo.SessionStore

/** Adds periodic playback-state metadata around KILO's existing queue snapshot store. */
class SessionPersistence(context: Context) {
    private val appContext = context.applicationContext
    private val lastSession = LastSessionRepository(appContext)

    fun save(player: Player, reason: String): PlaybackSnapshot? {
        if (player.mediaItemCount <= 0) {
            SessionStore.clearQueue()
            lastSession.clear()
            return null
        }
        val current = player.currentMediaItem ?: return null
        if (current.mediaId.startsWith("radio:")) return null

        val currentIndex = player.currentMediaItemIndex.coerceIn(0, (player.mediaItemCount - 1).coerceAtLeast(0))
        val start = (currentIndex - 10).coerceAtLeast(0)
        val end = minOf(player.mediaItemCount, start + MAX_TRACKS)
        val ids = (start until end).map { player.getMediaItemAt(it).mediaId }.filter(String::isNotBlank)
        val index = (currentIndex - start).coerceIn(0, (ids.size - 1).coerceAtLeast(0))
        val snapshot = PlaybackSnapshot(
            queue = QueueSnapshot(ids, index, current.mediaId),
            positionMs = player.currentPosition.coerceAtLeast(0L),
            playWhenReady = player.playWhenReady,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            savedAtEpochMs = System.currentTimeMillis()
        )
        runCatching { SessionStore.save(player) }
            .onFailure { Log.w(TAG, "legacy queue snapshot failed reason=$reason", it) }
        lastSession.save(snapshot)
        return snapshot
    }

    private companion object {
        const val TAG = "KiloSessionPersistence"
        const val MAX_TRACKS = 100
    }
}
