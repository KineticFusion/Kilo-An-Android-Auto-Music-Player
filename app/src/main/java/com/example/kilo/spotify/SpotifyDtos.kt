package com.example.kilo.spotify

import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual

@Serializable
data class SpotifySearchResponse(
    @Contextual val tracks: SpotifyPage<SpotifyTrackDto>? = null,
    @Contextual val artists: SpotifyPage<SpotifyArtistDto>? = null,
    @Contextual val albums: SpotifyPage<SpotifyAlbumDto>? = null,
    @Contextual val playlists: SpotifyPage<SpotifyPlaylistDto>? = null
)

@Serializable
data class SpotifyPage<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val next: String? = null,
    val previous: String? = null
)

@Serializable
data class SpotifyTrackDto(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<SpotifyArtistDto> = emptyList(),
    val album: SpotifyAlbumDto? = null,
    val duration_ms: Long = 0,
    val explicit: Boolean = false,
    val popularity: Int = 0,
    val preview_url: String? = null,
    val external_urls: Map<String, String> = emptyMap(),
    val images: List<SpotifyImageDto>? = null,
    val is_playable: Boolean = true,
    val track_number: Int = 0,
    val disc_number: Int = 0,
    val type: String = "track"
)

@Serializable
data class SpotifyArtistDto(
    val id: String,
    val uri: String,
    val name: String,
    val images: List<SpotifyImageDto> = emptyList(),
    val external_urls: Map<String, String> = emptyMap(),
    val followers: SpotifyFollowersDto? = null,
    val genres: List<String> = emptyList(),
    val popularity: Int = 0,
    val type: String = "artist"
)

@Serializable
data class SpotifyAlbumDto(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<SpotifyArtistDto> = emptyList(),
    val images: List<SpotifyImageDto> = emptyList(),
    val external_urls: Map<String, String> = emptyMap(),
    val release_date: String = "",
    val release_date_precision: String = "day",
    val total_tracks: Int = 0,
    val album_type: String = "album",
    val type: String = "album"
)

@Serializable
data class SpotifyPlaylistDto(
    val id: String,
    val uri: String,
    val name: String,
    val description: String = "",
    val images: List<SpotifyImageDto> = emptyList(),
    val external_urls: Map<String, String> = emptyMap(),
    val tracks: SpotifyPlaylistTracksDto? = null,
    val owner: SpotifyUserDto? = null,
    val public: Boolean = false,
    val collaborative: Boolean = false,
    val type: String = "playlist"
)

@Serializable
data class SpotifyPlaylistTracksDto(
    val total: Int = 0,
    val items: List<SpotifyPlaylistTrackItemDto> = emptyList()
)

@Serializable
data class SpotifyPlaylistTrackItemDto(
    val added_at: String = "",
    val added_by: SpotifyUserDto? = null,
    val track: SpotifyTrackDto? = null,
    val is_local: Boolean = false
)

@Serializable
data class SpotifyUserDto(
    val id: String,
    val uri: String,
    val display_name: String = "",
    val images: List<SpotifyImageDto> = emptyList(),
    val external_urls: Map<String, String> = emptyMap(),
    val followers: SpotifyFollowersDto? = null,
    val type: String = "user"
)

@Serializable
data class SpotifyFollowersDto(
    val total: Long = 0,
    val href: String? = null
)

@Serializable
data class SpotifyImageDto(
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0
)

@Serializable
data class SpotifySavedTrackDto(
    val added_at: String = "",
    val track: SpotifyTrackDto
)

@Serializable
data class SpotifySavedAlbumDto(
    val added_at: String = "",
    val album: SpotifyAlbumDto
)

@Serializable
data class SpotifyRecommendationsResponse(
    val tracks: List<SpotifyTrackDto> = emptyList(),
    val seeds: List<SpotifyRecommendationSeedDto> = emptyList()
)

@Serializable
data class SpotifyRecommendationSeedDto(
    val afterFilteringSize: Int = 0,
    val afterRelinkingSize: Int = 0,
    val href: String = "",
    val id: String = "",
    val initialPoolSize: Int = 0,
    val type: String = "",
    val genre: String? = null
)

@Serializable
data class SpotifyCategoriesResponse(
    @Contextual val categories: SpotifyPage<SpotifyCategoryDto>? = null
)

@Serializable
data class SpotifyCategoryDto(
    val id: String,
    val name: String,
    val icons: List<SpotifyImageDto> = emptyList(),
    val href: String = ""
)

@Serializable
data class SpotifyFeaturedPlaylistsResponse(
    @Contextual val playlists: SpotifyPage<SpotifyPlaylistDto>? = null,
    val message: String? = null
)

@Serializable
data class SpotifyNewReleasesResponse(
    @Contextual val albums: SpotifyPage<SpotifyAlbumDto>? = null
)

@Serializable
data class SpotifyFollowedArtistsResponse(
    @Contextual val artists: SpotifyPage<SpotifyArtistDto>? = null
)

@Serializable
data class SpotifyPlaybackStateResponse(
    val timestamp: Long = 0,
    val context: SpotifyPlaybackContextDto? = null,
    val progress_ms: Long = 0,
    val item: SpotifyTrackDto? = null,
    val currently_playing_type: String = "track",
    val actions: SpotifyActionsDto? = null,
    val is_playing: Boolean = false,
    val device: SpotifyDeviceDto? = null
)

@Serializable
data class SpotifyPlaybackContextDto(
    val type: String = "",
    val uri: String = "",
    val href: String = "",
    val external_urls: Map<String, String> = emptyMap()
)

@Serializable
data class SpotifyActionsDto(
    val disallows: SpotifyDisallowsDto? = null
)

@Serializable
data class SpotifyDisallowsDto(
    val pausing: Boolean = false,
    val resuming: Boolean = false,
    val seeking: Boolean = false,
    val skipping_next: Boolean = false,
    val skipping_prev: Boolean = false
)

@Serializable
data class SpotifyDeviceDto(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val volume_percent: Int? = null,
    val is_active: Boolean = false,
    val is_restricted: Boolean = false
)

@Serializable
data class SpotifyTopTracksResponse(
    val items: List<SpotifyTrackDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0
)

@Serializable
data class SpotifyTopArtistsResponse(
    val items: List<SpotifyArtistDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0
)

@Serializable
data class SpotifyErrorResponse(
    val error: SpotifyErrorDto? = null
)

@Serializable
data class SpotifyErrorDto(
    val status: Int = 0,
    val message: String = ""
)

@Serializable
data class SpotifyTokenResponse(
    val access_token: String = "",
    val token_type: String = "Bearer",
    val expires_in: Int = 3600,
    val refresh_token: String? = null,
    val scope: String = ""
)

@Serializable
data class SpotifyAccessTokenResponse(
    val accessToken: String = "",
    val accessTokenExpirationTimestampMs: Long = 0,
    val isAnonymous: Boolean = false
)

@Serializable
data class ClientTokenResponse(
    val clientToken: String = ""
)