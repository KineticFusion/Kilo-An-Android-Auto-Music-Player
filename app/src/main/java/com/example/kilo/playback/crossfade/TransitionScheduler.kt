package com.example.kilo.playback.crossfade

object TransitionScheduler {
    // Start preparing standby deck 30 seconds before transition (was 20s)
    const val PREPARE_WITHIN_MS = 30_000L
    // Reduced minimum buffer to 2 seconds for faster crossfade startup (was 5s)
    const val MINIMUM_BUFFER_MS = 2_000L
    // Additional safety buffer for reliable crossfade
    const val SAFE_FADE_BUFFER_MS = 3_000L

    fun shouldPrepare(remainingMs: Long, alreadyPrepared: Boolean): Boolean =
        !alreadyPrepared && remainingMs <= PREPARE_WITHIN_MS

    // Start crossfade when remaining time <= duration (was: remaining in 0..duration)
    // This ensures crossfade starts at the right moment
    fun shouldStart(remainingMs: Long, durationMs: Long, ready: Boolean, bufferedMs: Long): Boolean =
        ready && bufferedMs >= MINIMUM_BUFFER_MS && remainingMs <= durationMs

    // Check if we have enough buffer for a safe crossfade
    fun hasSafeBuffer(bufferedMs: Long): Boolean = bufferedMs >= SAFE_FADE_BUFFER_MS
}