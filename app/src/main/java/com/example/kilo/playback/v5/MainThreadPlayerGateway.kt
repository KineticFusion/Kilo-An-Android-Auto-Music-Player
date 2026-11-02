package com.example.kilo.playback.v5

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Main-looper boundary for commands sent to KILO's existing Media3 player. */
@UnstableApi
object MainThreadPlayerGateway {
    data class Snapshot(
        val mediaId: String?,
        val positionMs: Long,
        val bufferedPositionMs: Long,
        val durationMs: Long,
        val playbackState: Int,
        val isPlaying: Boolean,
        val playWhenReady: Boolean,
        val itemCount: Int,
        val currentIndex: Int
    )

    @Volatile private var playerHandler = Handler(Looper.getMainLooper())
    @Volatile private var player: ExoPlayer? = null

    fun attach(existingPlayer: ExoPlayer) {
        check(Looper.myLooper() == existingPlayer.applicationLooper) {
            "Player attachment must run on the player's application looper"
        }
        player = existingPlayer
        playerHandler = Handler(existingPlayer.applicationLooper)
    }

    fun isOnPlayerThread(): Boolean =
        player?.applicationLooper?.let { Looper.myLooper() == it } ?: (Looper.myLooper() == Looper.getMainLooper())

    fun snapshot(): Snapshot {
        requirePlayerThread()
        val active = requireNotNull(player) { "Playback gateway is not attached" }
        return Snapshot(
            mediaId = active.currentMediaItem?.mediaId,
            positionMs = active.currentPosition.coerceAtLeast(0L),
            bufferedPositionMs = active.bufferedPosition.coerceAtLeast(0L),
            durationMs = active.duration.coerceAtLeast(0L),
            playbackState = active.playbackState,
            isPlaying = active.isPlaying,
            playWhenReady = active.playWhenReady,
            itemCount = active.mediaItemCount,
            currentIndex = active.currentMediaItemIndex
        )
    }

    fun play() = onPlayer { it.play() }
    fun pause() = onPlayer { it.pause() }
    fun prepare() = onPlayer { it.prepare() }
    fun stop() = onPlayer { it.stop() }
    fun seekTo(positionMs: Long) = onPlayer { it.seekTo(positionMs.coerceAtLeast(0L)) }
    fun seekTo(index: Int, positionMs: Long) = onPlayer { it.seekTo(index, positionMs.coerceAtLeast(0L)) }
    fun setMediaItems(items: List<MediaItem>, index: Int, positionMs: Long) =
        onPlayer { it.setMediaItems(items, index, positionMs.coerceAtLeast(0L)) }
    fun addMediaItems(items: List<MediaItem>) = onPlayer { it.addMediaItems(items) }
    fun addMediaItems(index: Int, items: List<MediaItem>) = onPlayer { it.addMediaItems(index, items) }
    fun addMediaItem(index: Int, item: MediaItem) = onPlayer { it.addMediaItem(index, item) }
    fun setMediaSource(source: MediaSource) = onPlayer { it.setMediaSource(source) }
    fun setVolume(volume: Float) = onPlayer { it.volume = volume.coerceIn(0f, 1f) }
    fun setRepeatMode(mode: Int) = onPlayer { it.repeatMode = mode }
    fun setPlaybackSpeed(speed: Float) = onPlayer { it.setPlaybackSpeed(speed.coerceIn(0.5f, 2f)) }
    fun setSkipSilenceEnabled(enabled: Boolean) = onPlayer { it.setSkipSilenceEnabled(enabled) }

    /** Use from binder/network callbacks; the action is serialized on the player looper. */
    fun dispatch(action: (ExoPlayer) -> Unit) {
        val active = player ?: return
        if (Looper.myLooper() == active.applicationLooper) action(active)
        else playerHandler.post { if (player === active) action(active) }
    }

    fun post(action: () -> Unit) {
        val active = player ?: return
        if (Looper.myLooper() == active.applicationLooper) action()
        else playerHandler.post { if (player === active) action() }
    }

    suspend fun <T> withPlayer(action: (ExoPlayer) -> T): T = withContext(Dispatchers.Main.immediate) {
        val active = requireNotNull(player) { "Playback gateway is not attached" }
        action(active)
    }

    private inline fun onPlayer(action: (ExoPlayer) -> Unit) {
        requirePlayerThread()
        action(requireNotNull(player) { "Playback gateway is not attached" })
    }

    private fun requirePlayerThread() {
        check(isOnPlayerThread()) { "Media3 player call bypassed MainThreadPlayerGateway looper" }
    }
}
