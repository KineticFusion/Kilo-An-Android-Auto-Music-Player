package com.example.kilo.discovery.mixes

import com.example.kilo.companion.CompanionContext
import com.example.kilo.companion.ListeningEvent
import java.util.Calendar

class ContextProfiler {
    fun profile(events: List<ListeningEvent>, nowMs: Long = System.currentTimeMillis()): CompanionContext {
        val hour = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
        val moods = events.asReversed().asSequence().map { it.mood }.filter(String::isNotBlank).take(8).toList()
        val recentArtists = events.asReversed().asSequence().map { it.artist }.filter(String::isNotBlank).take(8).toList()
        return CompanionContext(nowMs = nowMs, hourOfDay = hour, recentMoods = moods, activeMood = moods.firstOrNull().orEmpty())
    }

    fun currentWindow(hour: Int): String = when (hour) {
        in 5..10 -> "morning"
        in 11..15 -> "afternoon"
        in 16..20 -> "evening"
        else -> "night"
    }
}
