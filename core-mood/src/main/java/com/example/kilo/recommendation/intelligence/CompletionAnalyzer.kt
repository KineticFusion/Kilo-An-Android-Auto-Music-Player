package com.example.kilo.recommendation.intelligence

import com.example.kilo.companion.ListeningEvent

object CompletionAnalyzer {
    fun weight(event: ListeningEvent): Float = when {
        event.action == "complete" -> 2f
        event.action == "listen" && event.durationMs > 0 && event.listenedMs >= event.durationMs * .8 -> 2f
        else -> 0f
    }
}
