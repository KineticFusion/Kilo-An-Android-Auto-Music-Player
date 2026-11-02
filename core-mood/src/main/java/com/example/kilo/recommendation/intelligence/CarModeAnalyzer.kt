package com.example.kilo.recommendation.intelligence

object CarModeAnalyzer {
    fun factors(context: RecommendationContext): Map<String, Float> =
        if (context.driving || context.androidAuto || context.carConnected) {
            mapOf("driving" to 95f, "sessionCompatibility" to 85f)
        } else if (context.headphones) {
            mapOf("focus" to 58f)
        } else if (context.bluetooth) {
            mapOf("sessionCompatibility" to 65f)
        } else emptyMap()
}
