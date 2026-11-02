package com.example.kilo

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.kilo.playback.v5.MainThreadPlayerGateway
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

/** Android Auto adapter over KILO's existing repository and the player's main-looper gateway. */
object AndroidAutoRepository {
    data class QueueSnapshot(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val isPlaying: Boolean
    )

    fun rememberSongs(songs: List<SongItemClean>) = PlaybackRepository.rememberAll(songs)

    fun dispatch(action: (ExoPlayer) -> Unit) = MainThreadPlayerGateway.dispatch(action)

    fun queueSnapshot(): ListenableFuture<QueueSnapshot> {
        val result = SettableFuture.create<QueueSnapshot>()
        MainThreadPlayerGateway.dispatch { player ->
            runCatching {
                val count = player.mediaItemCount
                QueueSnapshot(
                    items = (0 until count).map(player::getMediaItemAt),
                    index = player.currentMediaItemIndex.coerceAtLeast(0),
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    playWhenReady = player.playWhenReady,
                    isPlaying = player.isPlaying
                )
            }.onSuccess { result.set(it) }.onFailure { result.setException(it) }
        }
        return result
    }

    fun resumeOnCarConnect(
        fallbackItems: () -> Pair<List<MediaItem>, Pair<Int, Long>>,
        beforeSet: (ExoPlayer) -> Unit,
        beforePrepare: (ExoPlayer) -> Unit,
        onSaved: (ExoPlayer) -> Unit
    ) {
        MainThreadPlayerGateway.dispatch { player ->
            if (player.isPlaying) return@dispatch
            if (player.mediaItemCount == 0) {
                val (items, position) = fallbackItems()
                if (items.isNotEmpty()) {
                    val (index, startPositionMs) = position
                    beforeSet(player)
                    player.setMediaItems(items, index.coerceIn(0, items.lastIndex), startPositionMs.coerceAtLeast(0L))
                }
            }
            if (player.mediaItemCount > 0) {
                player.play()
                if (player.playbackState == androidx.media3.common.Player.STATE_IDLE ||
                    player.playbackState == androidx.media3.common.Player.STATE_ENDED
                ) {
                    beforePrepare(player)
                    player.prepare()
                }
                onSaved(player)
            }
        }
    }

    fun startQueue(
        items: List<MediaItem>,
        index: Int = 0,
        positionMs: Long = 0L,
        beforeSet: (ExoPlayer) -> Unit = {},
        beforePrepare: (ExoPlayer) -> Unit = {},
        onStarted: (ExoPlayer) -> Unit = {}
    ) {
        if (items.isEmpty()) return
        MainThreadPlayerGateway.dispatch { player ->
            beforeSet(player)
            player.setMediaItems(items, index.coerceIn(0, items.lastIndex), positionMs.coerceAtLeast(0L))
            player.play()
            beforePrepare(player)
            player.prepare()
            onStarted(player)
        }
    }
}
