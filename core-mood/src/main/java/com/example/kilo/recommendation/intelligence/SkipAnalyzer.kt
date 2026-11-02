package com.example.kilo.recommendation.intelligence

import com.example.kilo.companion.ListeningEvent

object SkipAnalyzer {
    fun weight(event: ListeningEvent): Float = when {
        event.action != "skip" -> 0f
        event.listenedMs < 5_000L -> -3f
        event.listenedMs < 30_000L -> -1.5f
        else -> -.4f
    }
}
