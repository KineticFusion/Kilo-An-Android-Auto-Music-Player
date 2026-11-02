package com.example.kilo.playback.crossfade

object TransitionScheduler {
    const val PREPARE_WITHIN_MS = 20_000L
    const val MINIMUM_BUFFER_MS = 5_000L

    fun shouldPrepare(remainingMs: Long, alreadyPrepared: Boolean): Boolean =
        !alreadyPrepared && remainingMs <= PREPARE_WITHIN_MS

    fun shouldStart(remainingMs: Long, durationMs: Long, ready: Boolean, bufferedMs: Long): Boolean =
        ready && bufferedMs >= MINIMUM_BUFFER_MS && remainingMs in 0L..durationMs
}
