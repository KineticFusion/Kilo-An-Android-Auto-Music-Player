package com.example.kilo.recommendation.genome

/** Numeric DNA for one recording. Names are identifiers only; they are never ranking features. */
data class TrackGenome(
    val trackId: String,
    val artistKey: String = "",
    val albumKey: String = "",
    val factors: Map<String, Float>,
    val genres: Map<String, Float> = emptyMap(),
    val languages: Map<String, Float> = emptyMap(),
    val confidence: Float = 0f,
    val updatedAtMs: Long = System.currentTimeMillis(),
    /** Evidence strength per factor; absent signals stay unknown instead of becoming neutral matches. */
    val factorConfidence: Map<String, Float> = emptyMap()
) {
    fun factor(key: String): Float = factors[key] ?: 50f
    fun evidence(key: String): Float = factorConfidence[key]?.coerceIn(0f, 1f) ?: 0f

    val energy get() = factor("energy")
    val danceability get() = factor("danceability")
    val darkness get() = factor("darkness")
    val brightness get() = factor("brightness")
    val driving get() = factor("driving")
    val focus get() = factor("focus")
    val study get() = factor("study")
    val workout get() = factor("workout")
    val night get() = factor("night")
    val lateNight get() = factor("lateNight")
    val electronic get() = factor("electronic")
    val acoustic get() = factor("acoustic")
    val instrumental get() = factor("instrumental")
    val vocalDensity get() = factor("vocalDensity")
    val bassIntensity get() = factor("bassIntensity")
    val aggression get() = factor("aggression")
    val nostalgia get() = factor("nostalgia")
    val dreaminess get() = factor("dreaminess")
    val atmosphere get() = factor("atmosphere")
    val tempo get() = factor("tempo")
    val emotion get() = factor("emotion")
    val melancholy get() = factor("melancholy")
    val uplift get() = factor("uplift")
    val warmth get() = factor("warmth")
    val coldness get() = factor("coldness")
    val urban get() = factor("urban")
    val cinematic get() = factor("cinematic")
    val regionalAffinity get() = factor("regionalAffinity")
    val eraAffinity get() = factor("eraAffinity")
    val discoveryScore get() = factor("discoveryScore")
    val mainstreamIndex get() = factor("mainstreamIndex")
    val repeatPotential get() = factor("repeatPotential")
    val sessionCompatibility get() = factor("sessionCompatibility")
    val familiarity get() = factor("familiarity")

    companion object {
        val DIMENSIONS = listOf(
            "energy", "danceability", "darkness", "brightness", "driving", "focus", "study", "workout",
            "night", "lateNight", "electronic", "acoustic", "instrumental", "vocalDensity", "bassIntensity",
            "aggression", "nostalgia", "dreaminess", "atmosphere", "tempo", "emotion", "melancholy", "uplift",
            "warmth", "coldness", "urban", "cinematic", "regionalAffinity", "eraAffinity", "discoveryScore",
            "mainstreamIndex", "repeatPotential", "sessionCompatibility", "familiarity"
        )

        fun normalized(genome: TrackGenome) = genome.copy(
            factors = DIMENSIONS.associateWith { (genome.factors[it] ?: 50f).coerceIn(0f, 100f) },
            genres = genome.genres.mapValues { it.value.coerceIn(0f, 100f) },
            languages = genome.languages.mapValues { it.value.coerceIn(0f, 100f) },
            confidence = genome.confidence.coerceIn(0f, 1f),
            factorConfidence = genome.factorConfidence.mapValues { it.value.coerceIn(0f, 1f) }
        )
    }
}
