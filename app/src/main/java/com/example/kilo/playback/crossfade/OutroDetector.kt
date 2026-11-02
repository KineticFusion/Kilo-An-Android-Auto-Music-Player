package com.example.kilo.playback.crossfade

object OutroDetector {
    fun likelyLongOutro(title: String, album: String): Boolean =
        sequenceOf(title, album).any { value -> listOf("outro", "reprise", "end credits").any { value.contains(it, true) } }
}
