package com.example.kilo.companion

/** Local, privacy-preserving inputs shared by the independent identity and discovery engines. */
data class CompanionProfile(
    val favoriteGenres: Set<String> = emptySet(),
    val favoriteMoods: Set<String> = emptySet(),
    val favoriteLanguages: Set<String> = emptySet(),
    val followedArtists: Set<String> = emptySet(),
    val likedTrackIds: Set<String> = emptySet(),
    val downloadedTrackIds: Set<String> = emptySet(),
    val excludedTrackIds: Set<String> = emptySet(),
    val discoveryLevel: String = "Balanced",
    val roadTripScore: Int = 0,
    val nightDriveScore: Int = 0,
    val workoutScore: Int = 0
)

data class CompanionTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String = "",
    val durationMs: Long = 0L,
    val genres: Set<String> = emptySet(),
    val moods: Set<String> = emptySet(),
    val language: String = "",
    val explicit: Boolean = false,
    val popularity: Int = 0,
    val trendDelta: Float = 0f,
    val playCount: Int = 0,
    val lastPlayedAtMs: Long = 0L,
    val isLiked: Boolean = false,
    val isDownloaded: Boolean = false
)

data class ListeningEvent(
    val trackId: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genres: Set<String> = emptySet(),
    val mood: String = "",
    val language: String = "",
    val atMs: Long,
    val listenedMs: Long = 0L,
    val durationMs: Long = 0L,
    val action: String = "listen",
    val liked: Boolean = false,
    val downloaded: Boolean = false
)

data class CompanionContext(
    val nowMs: Long = System.currentTimeMillis(),
    val hourOfDay: Int = 12,
    val temperatureCelsius: Float? = null,
    val drivingMode: Boolean = false,
    val recentMoods: List<String> = emptyList(),
    val activeMood: String = ""
)
