package com.example.kilo.playback.crossfade

import androidx.media3.common.Player
import kotlinx.coroutines.delay

object FadeController {
    suspend fun fade(player: Player, from: Float, to: Float, durationMs: Long, valid: () -> Boolean = { true }) {
        if (durationMs <= 0L) { player.volume = to.coerceIn(0f, 1f); return }
        val frameMs = 50L
        val frames = (durationMs / frameMs).coerceAtLeast(1L)
        for (frame in 1L..frames) {
            if (!valid()) return
            val linear = frame.toFloat() / frames
            val smooth = linear * linear * (3f - 2f * linear)
            player.volume = (from + (to - from) * smooth).coerceIn(0f, 1f)
            delay(frameMs)
        }
        if (valid()) player.volume = to.coerceIn(0f, 1f)
    }
}
