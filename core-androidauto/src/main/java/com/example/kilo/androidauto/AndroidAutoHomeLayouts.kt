package com.example.kilo.androidauto

data class AndroidAutoLayoutPreset(
    val id: String,
    val name: String,
    val description: String,
    val sections: List<String>
)

object AndroidAutoHomeLayouts {
    const val ORDER_PREFERENCE = "auto_home_order"

    val priorityChoices = listOf(
        "continue" to "Continue Listening",
        "liked" to "Liked Songs",
        "playlists" to "Playlists",
        "downloads" to "Downloads",
        "mood" to "Mood Hub",
        "recent" to "Recent Activity",
        "radio" to "Radio",
        "search" to "Search",
        "artists" to "Artists",
        "albums" to "Albums",
        "songs" to "Songs",
        "discovery" to "Discovery"
    )

    val defaultPriority = priorityChoices.map { it.first }

    val presets = listOf(
        AndroidAutoLayoutPreset("spotify", "Spotify Style", "Familiar, artwork-led shortcuts for everyday listening.", listOf("continue", "liked", "playlists", "downloads", "mood_hub", "recent")),
        AndroidAutoLayoutPreset("mood_first", "Mood First", "Put one-tap mood mixes at the top.", listOf("mood_night_drive", "mood_workout", "mood_focus", "mood_gaming", "mood_road_trip", "mood_relax", "recent_mood", "mood_radio")),
        AndroidAutoLayoutPreset("radio_first", "Radio First", "Keep live and discovery radio close.", listOf("artist_radio", "mood_radio", "discovery_radio", "hidden_gems_radio", "commute_radio", "road_trip_radio")),
        AndroidAutoLayoutPreset("driver", "Driver Mode", "Short labels and the fewest driving actions.", listOf("continue", "drive_mix", "mood_road_trip", "mood_night_drive", "liked", "downloads")),
        AndroidAutoLayoutPreset("minimal", "Minimal", "A quiet screen with four familiar destinations.", listOf("continue", "liked", "recent", "search")),
        AndroidAutoLayoutPreset("playlist_first", "Playlist First", "Your saved and downloaded listening first.", listOf("playlists", "downloads", "liked", "recent_playlists", "mood_hub", "mood_playlists")),
        AndroidAutoLayoutPreset("library_first", "Library First", "Browse your artists, albums, songs and playlists.", listOf("artists", "albums", "songs", "playlists", "downloads", "liked")),
        AndroidAutoLayoutPreset("discovery", "Discovery Mode", "Lead with new music and recommendations.", listOf("trending", "recommended_artists", "hidden_gems", "discovery_mix", "new_releases", "rising_artists")),
        AndroidAutoLayoutPreset("dj", "DJ Mode", "Keep a continuous mix and quick queue options nearby.", listOf("auto_mix", "party_mode", "mood_radio", "queue_control", "liked", "recently_added")),
        AndroidAutoLayoutPreset("smart", "KILO Smart Home", "A time- and listening-aware suggestion, with no automatic mood changes.", listOf("suggested_mood", "continue", "mood_hub", "recent", "radio", "liked"))
    )

    fun preset(id: String?): AndroidAutoLayoutPreset = presets.firstOrNull { it.id == id } ?: presets.first()

    fun normalizePriority(saved: String?): List<String> {
        val parsed = saved.orEmpty().split(',').map(String::trim).filter { it in defaultPriority }
        return (parsed + defaultPriority).distinct()
    }

    fun orderedSections(presetId: String?, savedPriority: String?): List<String> {
        val sections = preset(presetId).sections
        if (savedPriority.isNullOrBlank()) return sections
        val priority = normalizePriority(savedPriority)
        return sections.withIndex()
            .sortedWith(compareBy<IndexedValue<String>>({ priority.indexOf(priorityBucket(it.value)).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.index }))
            .map { it.value }
    }

    fun priorityBucket(section: String): String = when {
        section in setOf("mood_playlists") -> "playlists"
        section in setOf("mood_radio", "artist_radio", "discovery_radio", "hidden_gems_radio", "commute_radio", "road_trip_radio") -> "radio"
        section == "recent_mood" -> "recent"
        section.startsWith("mood") || section in setOf("suggested_mood", "drive_mix", "party_mode") -> "mood"
        section in setOf("auto_mix", "queue_control") -> "continue"
        section in setOf("trending", "recommended_artists", "hidden_gems", "discovery_mix", "new_releases", "rising_artists") -> "discovery"
        section in setOf("recent_playlists", "mood_playlists") -> "playlists"
        section == "recently_added" -> "recent"
        else -> section
    }
}
