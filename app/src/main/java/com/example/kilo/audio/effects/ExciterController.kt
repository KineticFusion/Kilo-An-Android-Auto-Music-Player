package com.example.kilo.audio.effects

/** Android's public session effects do not expose a harmonic exciter. */
class ExciterController {
    val supported: Boolean = false
    val status: String = "Unavailable without a PCM processing path"
}
