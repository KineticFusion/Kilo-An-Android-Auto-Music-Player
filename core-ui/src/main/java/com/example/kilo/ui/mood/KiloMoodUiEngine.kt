package com.example.kilo.ui.mood

import androidx.compose.ui.graphics.Color

data class KiloMoodVisual(
    val accent: Color,
    val supportingLine: String
)

object KiloMoodUiEngine {
    private val visuals = mapOf(
        "night drive" to KiloMoodVisual(Color(0xFF6E8BFF), "After-dark favorites"),
        "commute" to KiloMoodVisual(Color(0xFF42C9B7), "An easy start to the day"),
        "road trip" to KiloMoodVisual(Color(0xFFFFA64D), "Keep the miles moving"),
        "workout" to KiloMoodVisual(Color(0xFFFF5C67), "High-energy momentum"),
        "focus" to KiloMoodVisual(Color(0xFF7CBAFF), "Stay in your zone"),
        "relax" to KiloMoodVisual(Color(0xFFB58BFF), "Slow down and unwind"),
        "gaming" to KiloMoodVisual(Color(0xFFAD6BFF), "Power up your session"),
        "party" to KiloMoodVisual(Color(0xFFFF66B3), "Turn up the room"),
        "rain drive" to KiloMoodVisual(Color(0xFF67B9D8), "A softer soundtrack"),
        "romantic" to KiloMoodVisual(Color(0xFFFF7896), "Made for two"),
        "sleep" to KiloMoodVisual(Color(0xFF7788C9), "Drift off gently"),
        "happy" to KiloMoodVisual(Color(0xFFFFC04F), "A brighter kind of mix"),
        "sad" to KiloMoodVisual(Color(0xFF7796D5), "Songs that understand"),
        "coffee vibes" to KiloMoodVisual(Color(0xFFC68B62), "A little morning ritual")
    )

    fun visualFor(mood: String, fallback: Color): KiloMoodVisual =
        visuals[mood.trim().lowercase()] ?: KiloMoodVisual(fallback, "Picked for your moment")
}
