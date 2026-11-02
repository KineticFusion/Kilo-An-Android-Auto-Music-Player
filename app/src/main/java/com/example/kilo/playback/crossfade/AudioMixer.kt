package com.example.kilo.playback.crossfade

import androidx.media3.common.Player
import kotlin.math.max
import kotlin.math.sqrt

/** Android's audio mixer combines the two Media3 output tracks; this applies their envelopes. */
class AudioMixer(private val deckA: Player, private val deckB: Player) {
    fun apply(progress: Float, baseGain: Float, outgoingRms: Double? = null, incomingRms: Double? = null) {
        val gains = FadeCurveEngine.gains(progress)
        val compensation = if (outgoingRms != null && incomingRms != null) {
            val a = (gains.outgoing * outgoingRms).toDouble()
            val b = (gains.incoming * incomingRms).toDouble()
            val combined = sqrt(a * a + b * b)
            (max(outgoingRms, incomingRms) / combined.coerceAtLeast(0.0001)).coerceAtMost(1.0).toFloat()
        } else 1f
        deckA.volume = (baseGain * compensation * gains.outgoing).coerceIn(0f, 1f)
        deckB.volume = (baseGain * compensation * gains.incoming).coerceIn(0f, 1f)
    }

    fun mutePrimary() { deckA.volume = 0f }
    fun restorePrimary(baseGain: Float = 1f) {
        deckA.volume = baseGain.coerceIn(0f, 1f)
        deckB.volume = 0f
    }
}
