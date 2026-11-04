package com.example.kilo.audio.effects

/** Restricts level correction to profiles that also have a live limiter. */
class LoudnessController {
    fun safeInputGain(inputGainDb: Float, limiterAvailable: Boolean): Float =
        if (limiterAvailable) inputGainDb.coerceIn(-4f, 5.5f) else 0f
}
