package com.example.kilo.playback.crossfade

object FadeCurveEngine {
    fun gains(progress: Float): EnvelopeGenerator.Gains = EnvelopeGenerator.equalPower(progress)
}
