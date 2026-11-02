package com.example.kilo.recommendation.mood

import com.example.kilo.mood.MoodFeatureProfile
import com.example.kilo.recommendation.genome.TrackGenome

/** Numeric target profile. The selected label is an ID, never a song-matching feature. */
data class MoodVector(
    val key: String,
    val factors: Map<String, Float>,
    val genres: Map<String, Float> = emptyMap(),
    val relatedKeys: Set<String> = emptySet()
) {
    companion object {
        fun from(profile: MoodFeatureProfile): MoodVector = MoodVector(
            key = profile.name.trim().lowercase(),
            factors = defaultFactors(profile.name) + profile.attributes.entries.associate { (name, value) ->
                canonicalFactor(name) to value.coerceIn(0f, 100f)
            },
            genres = profile.genres.mapKeys { it.key.trim().lowercase().replace('_', ' ').replace('-', ' ') }
                .mapValues { it.value.coerceIn(0f, 1f) * 100f },
            relatedKeys = profile.relatedMoods.map { it.trim().lowercase() }.toSet()
        )

        fun canonicalFactor(name: String): String {
            val key = name.trim().lowercase().replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), "")
            return when (key) {
                "instrumentalness" -> "instrumental"
                "nightdrive" -> "night"
                "latenight" -> "lateNight"
                "discovery" -> "discoveryScore"
                "mainstream" -> "mainstreamIndex"
                "replay" -> "repeatPotential"
                else -> TrackGenome.DIMENSIONS.firstOrNull { it.lowercase() == key } ?: name.trim()
            }
        }

        /** Named presets only build the target vector; they are never compared with song text. */
        private fun defaultFactors(name: String): Map<String, Float> = when (name.trim().lowercase()) {
            "focus", "deep focus" -> mapOf("focus" to 100f, "instrumental" to 85f, "vocalDensity" to 18f, "tempo" to 20f, "energy" to 25f)
            "study" -> mapOf("study" to 100f, "focus" to 90f, "instrumental" to 88f, "vocalDensity" to 15f, "tempo" to 18f, "energy" to 20f)
            "night drive" -> mapOf("night" to 96f, "lateNight" to 82f, "driving" to 78f, "atmosphere" to 82f, "energy" to 60f, "tempo" to 50f, "dreaminess" to 65f)
            "workout" -> mapOf("workout" to 100f, "energy" to 95f, "tempo" to 90f, "danceability" to 75f, "bassIntensity" to 72f)
            "road trip" -> mapOf("driving" to 100f, "discoveryScore" to 85f, "familiarity" to 60f, "energy" to 70f, "tempo" to 65f)
            "relax", "chill" -> mapOf("energy" to 28f, "tempo" to 30f, "atmosphere" to 82f, "warmth" to 72f, "vocalDensity" to 45f)
            "rainy day", "rain drive" -> mapOf("atmosphere" to 86f, "dreaminess" to 90f, "melancholy" to 60f, "warmth" to 80f, "energy" to 40f, "tempo" to 42f, "driving" to 75f)
            "sleep" -> mapOf("energy" to 10f, "tempo" to 12f, "atmosphere" to 90f, "focus" to 85f, "instrumental" to 90f, "vocalDensity" to 10f)
            else -> emptyMap()
        }
    }
}
