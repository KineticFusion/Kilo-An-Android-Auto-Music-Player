package com.example.kilo.recommendation.intelligence

import com.example.kilo.companion.ListeningEvent

object ReplayAnalyzer {
    fun weight(event: ListeningEvent) = if (event.action == "replay") 3f else 0f
}
