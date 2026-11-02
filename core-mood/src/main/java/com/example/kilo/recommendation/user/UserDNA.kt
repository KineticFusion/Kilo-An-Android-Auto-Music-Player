package com.example.kilo.recommendation.user

/** Local listener profile used by the ranking layer; no display text is used as a song feature. */
data class UserDNA(
    val artistAffinity: Map<String, Float> = emptyMap(),
    val genreAffinity: Map<String, Float> = emptyMap(),
    val languageAffinity: Map<String, Float> = emptyMap(),
    val moodAffinity: Map<String, Float> = emptyMap(),
    val factorAffinity: Map<String, Float> = emptyMap(),
    val likedTrackIds: Set<String> = emptySet(),
    val excludedTrackIds: Set<String> = emptySet(),
    val discoveryPreference: Float = 50f,
    val preferredLanguageShare: Float = 85f,
    val tasteClusterTrackIds: Set<String> = emptySet()
)
