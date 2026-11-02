package com.example.kilo.playback.crossfade

object IntroDetector {
    /** Conservative metadata-only hint; waveform analysis is intentionally not run on the live path. */
    fun likelyLongIntro(title: String, album: String): Boolean =
        sequenceOf(title, album).any { value ->
            listOf("overture", "instrumental intro", "long intro").any { marker -> value.contains(marker, ignoreCase = true) }
        }
}
