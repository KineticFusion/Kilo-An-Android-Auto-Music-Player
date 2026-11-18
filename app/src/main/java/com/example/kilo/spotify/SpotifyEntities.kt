package com.example.kilo.spotify

import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual

@Serializable
data class SpotifyTrack(
    val id: String,
    val uri: String,
    val name: String,
    @Contextual val artists: List<SpotifyArtist> = emptyList(),
    @Contextual val album: SpotifyAlbum? = null,
    val durationMs: Long = 0,
    val explicit: Boolean = false,
    val popularity: Int = 0,
    val previewUrl: String? = null,
    val externalUrls: Map<String, String> = emptyMap(),
    @Contextual val images: List<SpotifyImage> = emptyList(),
    val isPlayable: Boolean = true,
    val trackNumber: Int = 0,
    val discNumber: Int = 0
)

@Serializable
data class SpotifyArtist(
    val id: String,
    val uri: String,
    val name: String,
    @Contextual val images: List<SpotifyImage> = emptyList(),
    val externalUrls: Map<String, String> = emptyMap(),
    val followers: Long = 0,
    val genres: List<String> = emptyList(),
    val popularity: Int = 0
)

@Serializable
data class SpotifyAlbum(
    val id: String,
    val uri: String,
    val name: String,
    @Contextual val artists: List<SpotifyArtist> = emptyList(),
    @Contextual val images: List<SpotifyImage> = emptyList(),
    val externalUrls: Map<String, String> = emptyMap(),
    val releaseDate: String = "",
    val releaseDatePrecision: String = "day",
    val totalTracks: Int = 0,
    val albumType: String = "album"
)

@Serializable
data class SpotifyPlaylist(
    val id: String,
    val uri: String,
    val name: String,
    val description: String = "",
    @Contextual val images: List<SpotifyImage> = emptyList(),
    val externalUrls: Map<String, String> = emptyMap(),
    val trackCount: Int = 0,
    @Contextual val owner: SpotifyUser? = null,
    val isPublic: Boolean = false,
    val isCollaborative: Boolean = false
)

@Serializable
data class SpotifyPlaylistTrack(
    val addedAt: String = "",
    @Contextual val addedBy: SpotifyUser? = null,
    @Contextual val track: SpotifyTrack? = null,
    val isLocal: Boolean = false
)

@Serializable
data class SpotifyUser(
    val id: String,
    val uri: String,
    val displayName: String = "",
    @Contextual val images: List<SpotifyImage> = emptyList(),
    val externalUrls: Map<String, String> = emptyMap(),
    val followers: Long = 0
)

@Serializable
data class SpotifyImage(
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0
) {
    val bestUrl: String
        get() = this.url
}

@Serializable
data class SpotifyCategory(
    val id: String,
    val name: String,
    @Contextual val icons: List<SpotifyImage> = emptyList(),
    val href: String = ""
)

@Serializable
data class SpotifyPlaybackState(
    val timestamp: Long = 0,
    @Contextual val context: SpotifyPlaybackContext? = null,
    val progressMs: Long = 0,
    @Contextual val item: SpotifyTrack? = null,
    val currentlyPlayingType: String = "track",
    @Contextual val actions: SpotifyActions? = null,
    val isPlaying: Boolean = false,
    @Contextual val device: SpotifyDevice? = null
)

@Serializable
data class SpotifyPlaybackContext(
    val type: String = "",
    val uri: String = "",
    val href: String = "",
    val externalUrls: Map<String, String> = emptyMap()
)

@Serializable
data class SpotifyActions(
    @Contextual val disallows: SpotifyDisallows? = null
)

@Serializable
data class SpotifyDisallows(
    val pausing: Boolean = false,
    val resuming: Boolean = false,
    val seeking: Boolean = false,
    val skippingNext: Boolean = false,
    val skippingPrev: Boolean = false
)

@Serializable
data class SpotifyDevice(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val volumePercent: Int? = null,
    val isActive: Boolean = false,
    val isRestricted: Boolean = false
)

@Serializable
data class SpotifyRecommendations(
    @Contextual val tracks: List<SpotifyTrack> = emptyList(),
    @Contextual val seeds: List<SpotifyRecommendationSeed> = emptyList()
)

@Serializable
data class SpotifyRecommendationSeed(
    val afterFilteringSize: Int = 0,
    val afterRelinkingSize: Int = 0,
    val href: String = "",
    val id: String = "",
    val initialPoolSize: Int = 0,
    val type: String = "",
    val genre: String? = null
)

sealed interface SpotifyResult<out T> {
    data class Success<T>(val value: T) : SpotifyResult<T>
    data class Failure(val error: SpotifyError) : SpotifyResult<Nothing>
}

inline fun <T, R> SpotifyResult<T>.map(transform: (T) -> R): SpotifyResult<R> {
    return when (this) {
        is SpotifyResult.Success -> SpotifyResult.Success(transform(value))
        is SpotifyResult.Failure -> this
    }
}

data class SpotifyError(
    val status: Int = 0,
    val message: String = "",
    val isRetryable: Boolean = false
)

@Serializable
data class SpotifySession(
    val accessToken: String = "",
    val tokenType: String = "Bearer",
    val expiresIn: Int = 3600,
    val refreshToken: String? = null,
    val scope: String = "",
    val expiresAt: Long = 0
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() >= expiresAt
}