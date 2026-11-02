package com.example.kilo.playback.crossfade

import androidx.media3.common.Player

/** Reuses KILO's single Media3 player and applies a bounded transition envelope; it never creates a second player. */
class AudioOverlapManager(private val player: Player) {
    suspend fun fadeOut(durationMs: Long, valid: () -> Boolean) =
        FadeController.fade(player, player.volume, 0f, durationMs, valid)
    suspend fun fadeIn(durationMs: Long, valid: () -> Boolean) =
        FadeController.fade(player, player.volume, 1f, durationMs, valid)
    fun restoreFullVolume() { player.volume = 1f }
}
