package com.example.kilo.audio.dsp

/** A true exciter needs PCM access, which is intentionally outside this session wrapper. */
class HarmonicExciter {
    val available: Boolean = false
    val explanation: String = "Not enabled: harmonic generation requires a PCM processing path."
}
