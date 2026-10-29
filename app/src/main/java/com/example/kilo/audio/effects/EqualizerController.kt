package com.example.kilo.audio.effects

/** Keeps profile bands inside the headroom limit before passing them to the native session adapter. */
class EqualizerController {
    fun boundedGains(gainsDb: List<Float>): List<Float> = gainsDb.map { it.coerceIn(-3f, 2.7f) }
}
