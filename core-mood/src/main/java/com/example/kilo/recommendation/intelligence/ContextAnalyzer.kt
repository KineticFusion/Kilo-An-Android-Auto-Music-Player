package com.example.kilo.recommendation.intelligence

import java.util.Calendar

data class RecommendationContext(
    val hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    val driving: Boolean = false,
    val androidAuto: Boolean = false,
    val bluetooth: Boolean = false,
    val headphones: Boolean = false,
    val activity: String = "",
    val weather: String = "",
    val carConnected: Boolean = false
)

object ContextAnalyzer {
    fun factors(context: RecommendationContext): Map<String, Float> =
        TimeAnalyzer.factors(context.hour) + CarModeAnalyzer.factors(context) + activityFactors(context.activity) +
            weatherFactors(context.weather)

    private fun activityFactors(activity: String) = when (activity.trim().lowercase()) {
        "workout", "gym", "running" -> mapOf("workout" to 92f, "energy" to 86f, "tempo" to 82f)
        "study", "coding", "work" -> mapOf("study" to 90f, "focus" to 90f, "vocalDensity" to 25f)
        "relax", "rest" -> mapOf("energy" to 30f, "atmosphere" to 75f)
        else -> emptyMap()
    }

    private fun weatherFactors(weather: String) = when (weather.trim().lowercase()) {
        "rain", "rainy", "storm" -> mapOf("atmosphere" to 78f, "dreaminess" to 70f, "warmth" to 60f)
        "sun", "sunny", "clear" -> mapOf("brightness" to 72f, "uplift" to 68f)
        else -> emptyMap()
    }
}
