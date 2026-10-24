package com.example.kilo

import com.example.kilo.companion.CompanionProfile
import com.example.kilo.companion.CompanionTrack
import com.example.kilo.companion.ListeningEvent
import com.example.kilo.profile.ListenerProfile

/** Converts existing KILO state into local-only inputs for the v2.5 companion engines. */
internal object CompanionFeatureAdapter {
    fun profile(
        profile: ListenerProfile,
        likedIds: Set<String>,
        downloadedIds: Set<String>,
        discoveryLevel: String = profile.discoveryLevel,
        roadTripScore: Int = 0,
        nightDriveScore: Int = 0,
        workoutScore: Int = 0
    ) = CompanionProfile(
        favoriteGenres = profile.favoriteGenres,
        favoriteMoods = profile.favoriteMoods,
        favoriteLanguages = profile.favoriteLanguages,
        followedArtists = profile.followedArtists.toSet(),
        likedTrackIds = likedIds,
        downloadedTrackIds = downloadedIds,
        discoveryLevel = discoveryLevel,
        roadTripScore = roadTripScore,
        nightDriveScore = nightDriveScore,
        workoutScore = workoutScore
    )

    fun tracks(
        songs: List<SongItemClean>,
        events: List<ListeningEvent>,
        likedIds: Set<String>,
        downloadedIds: Set<String>
    ): List<CompanionTrack> {
        val eventsByTrack = events.asSequence().filter { it.trackId.isNotBlank() }
            .groupBy { it.trackId }
        return songs.asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .take(MAX_CATALOG_SIZE)
            .map { song ->
                val history = eventsByTrack[song.id].orEmpty()
                CompanionTrack(
                    id = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    artworkUrl = song.coverUrl,
                    durationMs = song.durationMs,
                    language = languageOf(song.title),
                    playCount = history.count { it.action == "listen" || it.action == "complete" },
                    lastPlayedAtMs = history.maxOfOrNull { it.atMs } ?: 0L,
                    isLiked = song.id in likedIds,
                    isDownloaded = song.id in downloadedIds
                )
            }
            .toList()
    }

    fun languageOf(text: String): String {
        val codePoints = text.codePoints().toArray()
        fun containsScript(range: IntRange) = codePoints.any { it in range }
        return when {
            containsScript(0x0B80..0x0BFF) -> "Tamil"
            containsScript(0x0C00..0x0C7F) -> "Telugu"
            containsScript(0x0C80..0x0CFF) -> "Kannada"
            containsScript(0x0D00..0x0D7F) -> "Malayalam"
            containsScript(0x0980..0x09FF) -> "Bengali"
            containsScript(0x0A00..0x0A7F) -> "Punjabi"
            containsScript(0x0A80..0x0AFF) -> "Gujarati"
            containsScript(0x0B00..0x0B7F) -> "Odia"
            containsScript(0x0D80..0x0DFF) -> "Sinhala"
            containsScript(0x0E00..0x0E7F) -> "Thai"
            containsScript(0x1100..0x11FF) || containsScript(0xAC00..0xD7AF) -> "Korean"
            containsScript(0x3040..0x30FF) -> "Japanese"
            containsScript(0x0600..0x06FF) -> "Arabic"
            containsScript(0x0900..0x097F) -> "Hindi"
            else -> ""
        }
    }

    private const val MAX_CATALOG_SIZE = 300
}
