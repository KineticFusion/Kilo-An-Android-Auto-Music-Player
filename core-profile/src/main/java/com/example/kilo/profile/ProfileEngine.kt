package com.example.kilo.profile

import android.content.SharedPreferences
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.io.StringWriter

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
    private const val LEGACY_PROFILE_KEY = "listener_profile"
    private const val PROFILE_KEY_PREFIX = "listener_profile_"

    fun load(
        preferences: SharedPreferences,
        profileId: String = activeProfileId(preferences)
    ): ListenerProfile = runCatching {
        val key = profileKey(profileId)
        var serialized = preferences.getString(key, null)
        if (serialized == null) {
            // Migrate the former single global profile to the currently active listener.
            serialized = preferences.getString(LEGACY_PROFILE_KEY, null)
            if (serialized != null) {
                preferences.edit().putString(key, serialized).remove(LEGACY_PROFILE_KEY).apply()
            }
        }
        val json = JSONObject(serialized ?: "{}")
        ListenerProfile(
            favoriteGenres = json.optJSONArray("genres").toStringSet().take(1).toSet(),
            followedArtists = json.optJSONArray("artists").toStringList(),
            favoriteMoods = json.optJSONArray("moods").toStringSet().take(1).toSet(),
            discoveryLevel = json.optString("discovery", "Balanced"),
            audioPreference = json.optString("audio", "Balanced"),
            androidAutoUsage = json.optString("androidAuto", "Sometimes")
        )
    }.getOrDefault(ListenerProfile())

    fun save(
        preferences: SharedPreferences,
        profile: ListenerProfile,
        profileId: String = activeProfileId(preferences)
    ) {
        val json = JSONObject()
            .put("genres", JSONArray(profile.favoriteGenres.sorted().take(1)))
            .put("artists", JSONArray(profile.followedArtists.map(String::trim).filter(String::isNotBlank).distinct()))
            .put("moods", JSONArray(profile.favoriteMoods.sorted().take(1)))
            .put("discovery", profile.discoveryLevel)
            .put("audio", profile.audioPreference)
            .put("androidAuto", profile.androidAutoUsage)
        preferences.edit().putString(profileKey(profileId), json.toString()).apply()
    }

    fun clear(preferences: SharedPreferences, profileId: String = activeProfileId(preferences)) {
        preferences.edit().remove(profileKey(profileId)).remove(LEGACY_PROFILE_KEY).apply()
    }

    private fun activeProfileId(preferences: SharedPreferences): String =
        preferences.getString("active_account_id", "1").orEmpty().ifBlank { "1" }

    private fun profileKey(profileId: String): String =
        PROFILE_KEY_PREFIX + profileId.ifBlank { "1" }

    fun exportXml(profile: ListenerProfile): String {
        val output = StringWriter()
        val xml = Xml.newSerializer()
        xml.setOutput(output)
        xml.startDocument("UTF-8", true)
        xml.startTag("", "kiloProfile")
        xml.attribute("", "version", "1")
        fun values(tag: String, values: Iterable<String>) {
            xml.startTag("", tag)
            values.forEach { value -> xml.startTag("", "item"); xml.text(value); xml.endTag("", "item") }
            xml.endTag("", tag)
        }
        values("genres", profile.favoriteGenres.sorted().take(1))
        values("moods", profile.favoriteMoods.sorted().take(1))
        values("artists", profile.followedArtists.map(String::trim).filter(String::isNotBlank).distinct().take(50))
        fun value(tag: String, text: String) {
            xml.startTag("", tag); xml.text(text); xml.endTag("", tag)
        }
        value("discovery", profile.discoveryLevel)
        value("audio", profile.audioPreference)
        value("androidAuto", profile.androidAutoUsage)
        xml.endTag("", "kiloProfile")
        xml.endDocument()
        return output.toString()
    }

    fun parseXml(serialized: String): ListenerProfile? = runCatching {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(serialized))
        val genres = linkedSetOf<String>()
        val moods = linkedSetOf<String>()
        val artists = mutableListOf<String>()
        var discovery = "Balanced"
        var audio = "Balanced"
        var androidAuto = "Sometimes"
        var rootFound = false
        var section = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "kiloProfile" -> rootFound = true
                    "genres", "moods", "artists" -> section = parser.name
                    "item" -> {
                        val value = parser.nextText().trim()
                        when (section) {
                            "genres" -> if (genres.isEmpty() && value.isNotBlank()) genres += value
                            "moods" -> if (moods.isEmpty() && value.isNotBlank()) moods += value
                            "artists" -> if (value.isNotBlank() && artists.size < 50) artists += value
                        }
                    }
                    "discovery" -> discovery = parser.nextText().trim().ifBlank { "Balanced" }
                    "audio" -> audio = parser.nextText().trim().ifBlank { "Balanced" }
                    "androidAuto" -> androidAuto = parser.nextText().trim().ifBlank { "Sometimes" }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == section) {
                section = ""
            }
            event = parser.next()
        }
        require(rootFound) { "Not a KILO profile XML file" }
        ListenerProfile(genres, artists.distinct().take(50), moods, discovery, audio, androidAuto)
    }.getOrNull()

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
