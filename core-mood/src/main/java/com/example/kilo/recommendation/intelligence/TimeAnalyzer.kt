package com.example.kilo.recommendation.intelligence

object TimeAnalyzer {
    fun factors(hour: Int): Map<String, Float> = when (hour.coerceIn(0, 23)) {
        in 5..9 -> mapOf("energy" to 62f, "brightness" to 68f, "uplift" to 64f)
        in 17..19 -> mapOf("warmth" to 66f, "energy" to 54f)
        in 20..23, in 0..4 -> mapOf("night" to 90f, "lateNight" to 88f, "atmosphere" to 70f)
        else -> emptyMap()
    }
}
