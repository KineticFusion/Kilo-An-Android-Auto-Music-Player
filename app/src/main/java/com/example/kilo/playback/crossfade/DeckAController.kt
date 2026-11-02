package com.example.kilo.playback.crossfade

import androidx.media3.common.Player

/** Deck A is always KILO's existing session/queue player. */
class DeckAController(val player: Player) {
    val mediaId: String get() = player.currentMediaItem?.mediaId.orEmpty()
    val isPlaying: Boolean get() = player.isPlaying
    val remainingMs: Long
        get() = if (player.duration > 0L && player.duration != androidx.media3.common.C.TIME_UNSET)
            (player.duration - player.currentPosition).coerceAtLeast(0L) else Long.MAX_VALUE
}
