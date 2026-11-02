package com.example.kilo.recommendation.intelligence

object ListeningPatternEngine {
    fun discoveryPreference(level: String): Float = when (level.trim().lowercase()) {
        "low" -> 25f
        "high" -> 80f
        "very high" -> 95f
        else -> 50f
    }
}
