package com.example.kilo.playback.crossfade

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Equal-power pair: cos/sin envelopes keep expected combined energy approximately constant. */
object EnvelopeGenerator {
    data class Gains(val outgoing: Float, val incoming: Float)

    fun equalPower(progress: Float): Gains {
        val p = progress.coerceIn(0f, 1f).toDouble() * PI / 2.0
        return Gains(cos(p).toFloat(), sin(p).toFloat())
    }
}
