package com.example.kilo.playback.crossfade

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** One reusable auxiliary player; it holds no more than the next queue item. */
class DeckBController(val player: Player) {
    var state: DeckState = DeckState.IDLE
        private set
    val mediaId: String get() = player.currentMediaItem?.mediaId.orEmpty()
    val bufferedMs: Long get() = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
    val ready: Boolean get() = player.playbackState == Player.STATE_READY && mediaId.isNotBlank()

    fun preparing(item: MediaItem) {
        state = DeckState.PREPARING
        player.volume = 0f
        player.setMediaItem(item)
        player.prepare()
    }

    fun markReady() { if (state == DeckState.PREPARING) state = DeckState.READY }
    fun markFadingIn() { state = DeckState.FADING_IN }
    fun markActive() { state = DeckState.ACTIVE }
    fun markFadingOut() { state = DeckState.FADING_OUT }
    fun markComplete() { state = DeckState.COMPLETE }
    fun clear() {
        player.pause()
        player.clearMediaItems()
        player.volume = 0f
        state = DeckState.IDLE
    }
}
