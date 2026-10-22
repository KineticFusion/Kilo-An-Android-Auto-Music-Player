package com.example.kilo.profile

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class ListenerProfile(
    val favoriteGenres: Set<String> = emptySet(),
    val followedArtists: List<String> = emptyList(),
    val favoriteMoods: Set<String> = emptySet(),
    val discoveryLevel: String = "Balanced",
    val audioPreference: String = "Balanced",
    val androidAutoUsage: String = "Sometimes"
)

/** Local-only profile storage and recommendation context for KILO's existing app architecture. */
object ProfileEngine {
    private const val PROFILE_KEY = "listener_profile"

    fun load(preferences: SharedPreferences): ListenerProfile = runCatching {
        val json = JSONObject(preferences.getString(PROFILE_KEY, "{}") ?: "{}")
        ListenerProfile(
            favoriteGenres = json.optJSONArray("genres").toStringSet(),
            followedArtists = json.optJSONArray("artists").toStringList(),
            favoriteMoods = json.optJSONArray("moods").toStringSet(),
            discoveryLevel = json.optString("discovery", "Balanced"),
            audioPreference = json.optString("audio", "Balanced"),
            androidAutoUsage = json.optString("androidAuto", "Sometimes")
        )
    }.getOrDefault(ListenerProfile())

    fun save(preferences: SharedPreferences, profile: ListenerProfile) {
        val json = JSONObject()
            .put("genres", JSONArray(profile.favoriteGenres.sorted()))
            .put("artists", JSONArray(profile.followedArtists.map(String::trim).filter(String::isNotBlank).distinct()))
            .put("moods", JSONArray(profile.favoriteMoods.sorted()))
            .put("discovery", profile.discoveryLevel)
            .put("audio", profile.audioPreference)
            .put("androidAuto", profile.androidAutoUsage)
        preferences.edit().putString(PROFILE_KEY, json.toString()).apply()
    }

    fun export(profile: ListenerProfile): String = JSONObject()
        .put("genres", JSONArray(profile.favoriteGenres.sorted()))
        .put("artists", JSONArray(profile.followedArtists))
        .put("moods", JSONArray(profile.favoriteMoods.sorted()))
        .put("discovery", profile.discoveryLevel)
        .put("audio", profile.audioPreference)
        .put("androidAuto", profile.androidAutoUsage)
        .toString(2)

    private fun JSONArray?.toStringList(): List<String> = this?.let { array ->
        (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank)
    }.orEmpty()

    private fun JSONArray?.toStringSet(): Set<String> = toStringList().toSet()
}

val PERSONALIZATION_GENRES = listOf(
    "Hip-Hop", "Rap", "Trap", "EDM", "Rock", "Metal", "Alternative", "Pop", "Jazz", "Classical",
    "Lofi", "Phonk", "Synthwave", "Country", "K-Pop"
)

val PERSONALIZATION_MOODS = listOf(
    "Focus", "Study", "Coding", "Workout", "Night Drive", "Road Trip", "Commute", "Gaming", "Relax",
    "Sleep", "Party", "High Energy", "Happy", "Sad", "Romantic", "Nostalgic"
)
