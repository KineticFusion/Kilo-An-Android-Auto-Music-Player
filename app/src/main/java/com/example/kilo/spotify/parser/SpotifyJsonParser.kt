package com.example.kilo.spotify.parser

import com.example.kilo.spotify.SpotifyError
import com.example.kilo.spotify.SpotifyTrack
import com.example.kilo.spotify.SpotifyArtist
import com.example.kilo.spotify.SpotifyAlbum
import com.example.kilo.spotify.SpotifyPlaylist
import com.example.kilo.spotify.SpotifyPlaylistTrack
import com.example.kilo.spotify.SpotifyImage
import com.example.kilo.spotify.SpotifyCategory
import com.example.kilo.spotify.SpotifyRecommendations
import com.example.kilo.spotify.SpotifyRecommendationSeed
import com.example.kilo.spotify.SpotifyUser
import com.example.kilo.spotify.SpotifySearchResponse
import kotlinx.serialization.json.Json

object SpotifyJsonParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseErrorResponse(jsonString: String): SpotifyError? {
        try {
            val obj = json.decodeFromString<com.example.kilo.spotify.SpotifyErrorResponse>(jsonString)
            return obj.error?.let {
                SpotifyError(
                    status = it.status,
                    message = it.message,
                    isRetryable = it.status in 429..599
                )
            }
        } catch (e: Exception) {
            return null
        }
    }

    // Parse SearchResponse
    fun parseSearchResponse(jsonString: String): SpotifySearchResponse? {
        return try {
            json.decodeFromString<SpotifySearchResponse>(jsonString)
        } catch (e: Exception) {
            null
        }
    }

    // Parse Track DTO to Entity
    fun parseTrackDto(dto: com.example.kilo.spotify.SpotifyTrackDto?): SpotifyTrack? {
        return dto?.let {
            val artists = it.artists.mapNotNull { parseArtistDto(it) }
            val album = it.album?.let { parseAlbumDto(it) }
            val images = it.images?.mapNotNull { parseImageDto(it) } ?: emptyList()
            SpotifyTrack(
                id = it.id,
                uri = it.uri,
                name = it.name,
                artists = artists,
                album = album,
                durationMs = it.duration_ms,
                explicit = it.explicit,
                popularity = it.popularity,
                previewUrl = it.preview_url,
                externalUrls = it.external_urls,
                images = images,
                isPlayable = it.is_playable,
                trackNumber = it.track_number,
                discNumber = it.disc_number
            )
        }
    }

    // Parse Artist DTO to Entity
    fun parseArtistDto(dto: com.example.kilo.spotify.SpotifyArtistDto?): SpotifyArtist? {
        return dto?.let {
            val images = it.images.mapNotNull { parseImageDto(it) }
            SpotifyArtist(
                id = it.id,
                uri = it.uri,
                name = it.name,
                images = images,
                externalUrls = it.external_urls,
                followers = it.followers?.total ?: 0,
                genres = it.genres,
                popularity = it.popularity
            )
        }
    }

    // Parse Album DTO to Entity
    fun parseAlbumDto(dto: com.example.kilo.spotify.SpotifyAlbumDto?): SpotifyAlbum? {
        return dto?.let {
            val artists = it.artists.mapNotNull { parseArtistDto(it) }
            val images = it.images.mapNotNull { parseImageDto(it) }
            SpotifyAlbum(
                id = it.id,
                uri = it.uri,
                name = it.name,
                artists = artists,
                images = images,
                externalUrls = it.external_urls,
                releaseDate = it.release_date,
                releaseDatePrecision = it.release_date_precision,
                totalTracks = it.total_tracks,
                albumType = it.album_type
            )
        }
    }

    // Parse Playlist DTO to Entity
    fun parsePlaylistDto(dto: com.example.kilo.spotify.SpotifyPlaylistDto?): SpotifyPlaylist? {
        return dto?.let {
            val images = it.images.mapNotNull { parseImageDto(it) }
            val owner = it.owner?.let { parseUserDto(it) }
            SpotifyPlaylist(
                id = it.id,
                uri = it.uri,
                name = it.name,
                description = it.description ?: "",
                images = images,
                externalUrls = it.external_urls,
                trackCount = it.tracks?.total ?: 0,
                owner = owner,
                isPublic = it.public,
                isCollaborative = it.collaborative
            )
        }
    }

    // Parse Playlist Track Item DTO
    fun parsePlaylistTrackItemDto(dto: com.example.kilo.spotify.SpotifyPlaylistTrackItemDto?): SpotifyPlaylistTrack? {
        return dto?.let {
            val track = it.track?.let { parseTrackDto(it) }
            val addedBy = it.added_by?.let { parseUserDto(it) }
            SpotifyPlaylistTrack(
                addedAt = it.added_at,
                addedBy = addedBy,
                track = track,
                isLocal = it.is_local
            )
        }
    }

    // Parse Saved Track DTO
    fun parseSavedTrackDto(dto: com.example.kilo.spotify.SpotifySavedTrackDto?): SpotifyTrack? {
        return dto?.track?.let { parseTrackDto(it) }
    }

    // Parse Saved Album DTO
    fun parseSavedAlbumDto(dto: com.example.kilo.spotify.SpotifySavedAlbumDto?): SpotifyAlbum? {
        return dto?.album?.let { parseAlbumDto(it) }
    }

    // Parse Category DTO
    fun parseCategoryDto(dto: com.example.kilo.spotify.SpotifyCategoryDto?): SpotifyCategory? {
        return dto?.let {
            val icons = it.icons.mapNotNull { parseImageDto(it) }
            SpotifyCategory(
                id = it.id,
                name = it.name,
                icons = icons,
                href = it.href
            )
        }
    }

    // Parse Recommendation Seed DTO
    fun parseRecommendationSeedDto(dto: com.example.kilo.spotify.SpotifyRecommendationSeedDto?): SpotifyRecommendationSeed? {
        return dto?.let {
            SpotifyRecommendationSeed(
                afterFilteringSize = it.afterFilteringSize,
                afterRelinkingSize = it.afterRelinkingSize,
                href = it.href,
                id = it.id,
                initialPoolSize = it.initialPoolSize,
                type = it.type,
                genre = it.genre
            )
        }
    }

    // Parse User DTO
    fun parseUserDto(dto: com.example.kilo.spotify.SpotifyUserDto?): SpotifyUser? {
        return dto?.let {
            val images = it.images.mapNotNull { parseImageDto(it) }
            SpotifyUser(
                id = it.id,
                uri = it.uri,
                displayName = it.display_name,
                images = images,
                externalUrls = it.external_urls,
                followers = it.followers?.total ?: 0
            )
        }
    }

    // Parse Image DTO
    fun parseImageDto(dto: com.example.kilo.spotify.SpotifyImageDto?): SpotifyImage? {
        return dto?.let {
            SpotifyImage(
                url = it.url,
                width = it.width,
                height = it.height
            )
        }
    }

    // Extract best image URL from list of DTOs
    fun extractBestImageUrlDto(images: List<com.example.kilo.spotify.SpotifyImageDto>?): String? {
        var best: com.example.kilo.spotify.SpotifyImageDto? = null
        var bestSize = 0
        images?.forEach { img ->
            val size = img.width * img.height
            if (size > bestSize) {
                bestSize = size
                best = img
            }
        }
        return best?.url
    }

    // Extract best image URL from list of entities
    fun extractBestImageUrl(images: List<SpotifyImage>?): String? {
        var best: SpotifyImage? = null
        var bestSize = 0
        images?.forEach { img ->
            val size = img.width * img.height
            if (size > bestSize) {
                bestSize = size
                best = img
            }
        }
        return best?.url
    }
}