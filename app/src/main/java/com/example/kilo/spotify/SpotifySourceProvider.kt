package com.example.kilo.spotify

import android.content.Context
import android.util.Log
import com.example.kilo.streaming.AudioSourceProvider
import com.example.kilo.streaming.ConfiguredSource
import com.example.kilo.streaming.HybridEngine
import com.example.kilo.streaming.HybridSourceRegistry
import com.example.kilo.streaming.HybridTrack
import com.example.kilo.streaming.ResolvedStream
import com.example.kilo.streaming.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Spotify Source Provider for Hybrid Engine
 * Provides catalog metadata from Spotify, delegates audio to HybridEngine
 */
class SpotifySourceProvider(
    context: Context,
    override val config: ConfiguredSource
) : AudioSourceProvider {

    private val appContext = context.applicationContext
    private val TAG = "KiloSpotifySource"

    override suspend fun search(query: String, limit: Int): List<HybridTrack> = withContext(Dispatchers.IO) {
        if (!config.enabled || query.isBlank()) return@withContext emptyList()

        runCatching {
            val result = SpotifyApiClient.search(appContext, query, limit = limit)
            result?.tracks?.items?.mapNotNull { track ->
                val artists = track.artists.map { it.name }.joinToString(", ")
                val albumName = track.album?.name ?: ""
                val coverUrl = track.album?.images?.firstOrNull()?.url ?: ""
                val albumImages = track.album?.images ?: emptyList()
                val bestImage = albumImages.maxByOrNull { it.width * it.height }?.url ?: coverUrl

                HybridTrack(
                    canonicalId = "spotify:${track.id}",
                    rawId = track.id,
                    sourceId = config.id,
                    title = track.name,
                    artist = artists,
                    album = albumName,
                    coverUrl = bestImage,
                    durationMs = track.durationMs,
                    codecBadge = "Spotify • Hybrid",
                    isLossless = false,
                    isExplicit = track.explicit
                )
            } ?: emptyList()
        }.getOrElse { err ->
            Log.w(TAG, "Spotify search failed: ${err.message}")
            emptyList()
        }
    }

    override suspend fun resolveStream(
        trackId: String,
        title: String,
        artist: String,
        durationMs: Long,
        preferLossless: Boolean
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        // Spotify doesn't provide direct audio streams
        // Delegate to HybridEngine which will try YouTube Music or addons
        if (!config.enabled) return@withContext null

        return@withContext HybridEngine.resolvePlayableStream(
            context = appContext,
            canonicalId = trackId,
            title = title,
            artist = artist,
            durationMs = durationMs,
            preferLossless = preferLossless
        )
    }

    override fun invalidateTrack(trackId: String) {
        HybridEngine.invalidateStream(trackId)
    }

    /**
     * Get user's liked tracks
     */
    suspend fun fetchLikedTracks(limit: Int = 50): List<HybridTrack> = withContext(Dispatchers.IO) {
        runCatching {
            SpotifyApiClient.getLikedTracks(appContext, limit)?.mapNotNull { track ->
                val artists = track.artists.map { it.name }.joinToString(", ")
                val albumName = track.album?.name ?: ""
                val coverUrl = track.album?.images?.firstOrNull()?.url ?: ""
                val albumImages = track.album?.images ?: emptyList()
                val bestImage = albumImages.maxByOrNull { it.width * it.height }?.url ?: coverUrl

                HybridTrack(
                    canonicalId = "spotify:${track.id}",
                    rawId = track.id,
                    sourceId = config.id,
                    title = track.name,
                    artist = artists,
                    album = albumName,
                    coverUrl = bestImage,
                    durationMs = track.durationMs,
                    codecBadge = "Spotify • Hybrid",
                    isLossless = false,
                    isExplicit = track.explicit
                )
            } ?: emptyList()
        }.getOrElse { err ->
            Log.w(TAG, "Failed to fetch liked tracks: ${err.message}")
            emptyList()
        }
    }

    /**
     * Get user's playlists
     */
    suspend fun fetchUserPlaylists(limit: Int = 30): List<SpotifyPlaylistSummary> = withContext(Dispatchers.IO) {
        runCatching {
            SpotifyApiClient.getUserPlaylists(appContext, limit)?.map { playlist ->
                SpotifyPlaylistSummary(
                    id = playlist.id,
                    name = playlist.name,
                    coverUrl = playlist.images.firstOrNull()?.url ?: "",
                    trackCount = playlist.trackCount
                )
            } ?: emptyList()
        }.getOrElse { err ->
            Log.w(TAG, "Failed to fetch user playlists: ${err.message}")
            emptyList()
        }
    }

    /**
     * Get playlist tracks
     */
    suspend fun fetchPlaylistTracks(playlistId: String, limit: Int = 100): List<HybridTrack> = withContext(Dispatchers.IO) {
        runCatching {
            SpotifyApiClient.getPlaylistTracks(appContext, playlistId, limit)?.mapNotNull { track ->
                val artists = track.artists.map { it.name }.joinToString(", ")
                val albumName = track.album?.name ?: ""
                val coverUrl = track.album?.images?.firstOrNull()?.url ?: ""
                val albumImages = track.album?.images ?: emptyList()
                val bestImage = albumImages.maxByOrNull { it.width * it.height }?.url ?: coverUrl

                HybridTrack(
                    canonicalId = "spotify:${track.id}",
                    rawId = track.id,
                    sourceId = config.id,
                    title = track.name,
                    artist = artists,
                    album = albumName,
                    coverUrl = bestImage,
                    durationMs = track.durationMs,
                    codecBadge = "Spotify • Hybrid",
                    isLossless = false,
                    isExplicit = track.explicit
                )
            } ?: emptyList()
        }.getOrElse { err ->
            Log.w(TAG, "Failed to fetch playlist tracks: ${err.message}")
            emptyList()
        }
    }

    /**
     * Get home feed recommendations
     */
    suspend fun fetchHomeRecommendations(limit: Int = 25): List<HybridTrack> = withContext(Dispatchers.IO) {
        runCatching {
            // Use recommendations based on user's top tracks
            val topTracks = SpotifyApiClient.getLikedTracks(appContext, 5) ?: emptyList()
            val seedTracks = topTracks.take(5).map { it.id }

            SpotifyApiClient.getRecommendations(appContext, seedTracks = seedTracks, limit = limit)?.mapNotNull { track ->
                val artists = track.artists.map { it.name }.joinToString(", ")
                val albumName = track.album?.name ?: ""
                val coverUrl = track.album?.images?.firstOrNull()?.url ?: ""
                val albumImages = track.album?.images ?: emptyList()
                val bestImage = albumImages.maxByOrNull { it.width * it.height }?.url ?: coverUrl

                HybridTrack(
                    canonicalId = "spotify:${track.id}",
                    rawId = track.id,
                    sourceId = config.id,
                    title = track.name,
                    artist = artists,
                    album = albumName,
                    coverUrl = bestImage,
                    durationMs = track.durationMs,
                    codecBadge = "Spotify • Hybrid",
                    isLossless = false,
                    isExplicit = track.explicit
                )
            } ?: emptyList()
        }.getOrElse { err ->
            Log.w(TAG, "Failed to fetch home recommendations: ${err.message}")
            emptyList()
        }
    }

    /**
     * Search and return as HybridTrack list for home shelves
     */
    suspend fun searchForHome(query: String, limit: Int = 20): List<HybridTrack> = withContext(Dispatchers.IO) {
        runCatching {
            SpotifyApiClient.search(appContext, query, listOf("track"), limit)?.tracks?.items?.mapNotNull { track ->
                val artists = track.artists.map { it.name }.joinToString(", ")
                val albumName = track.album?.name ?: ""
                val coverUrl = track.album?.images?.firstOrNull()?.url ?: ""
                val albumImages = track.album?.images ?: emptyList()
                val bestImage = albumImages.maxByOrNull { it.width * it.height }?.url ?: coverUrl

                HybridTrack(
                    canonicalId = "spotify:${track.id}",
                    rawId = track.id,
                    sourceId = config.id,
                    title = track.name,
                    artist = artists,
                    album = albumName,
                    coverUrl = bestImage,
                    durationMs = track.durationMs,
                    codecBadge = "Spotify • Hybrid",
                    isLossless = false,
                    isExplicit = track.explicit
                )
            } ?: emptyList()
        }.getOrElse { err ->
            Log.w(TAG, "Spotify home search failed: ${err.message}")
            emptyList()
        }
    }

    // ============= Native Spotify Browsing (Yuma-style) =============

    /**
     * Get new releases for home feed
     */
    suspend fun fetchNewReleases(limit: Int = 20): List<HybridTrack> = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext emptyList()
        runCatching {
            // Use recommendations with "new music" genre seed for new releases
            // The new releases endpoint doesn't include tracks directly
            SpotifyApiClient.getRecommendations(
                context = appContext,
                seedGenres = listOf("new-music", "pop", "rock", "electronic", "hip-hop"),
                limit = limit
            )?.mapNotNull { track ->
                val artists = track.artists.map { it.name }.joinToString(", ")
                val coverUrl = track.album?.images?.firstOrNull()?.url ?: ""
                HybridTrack(
                    canonicalId = "spotify:${track.id}",
                    rawId = track.id,
                    sourceId = config.id,
                    title = track.name,
                    artist = artists,
                    album = track.album?.name ?: "",
                    coverUrl = coverUrl,
                    durationMs = track.durationMs,
                    codecBadge = "Spotify • Hybrid",
                    isLossless = false,
                    isExplicit = track.explicit
                )
            } ?: emptyList()
        }.getOrElse { err ->
            Log.w(TAG, "Failed to fetch new releases: ${err.message}")
            emptyList()
        }
    }

    /**
     * Get featured playlists for home feed
     */
    suspend fun fetchFeaturedPlaylists(limit: Int = 10): List<SpotifyPlaylistSummary> = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext emptyList()
        runCatching {
            SpotifyApiClient.getFeaturedPlaylists(appContext, limit = limit)?.items?.map { playlist ->
                SpotifyPlaylistSummary(
                    id = playlist.id,
                    name = playlist.name,
                    coverUrl = playlist.images.firstOrNull()?.url ?: "",
                    trackCount = playlist.trackCount
                )
            } ?: emptyList()
        }.getOrElse { err ->
            Log.w(TAG, "Failed to fetch featured playlists: ${err.message}")
            emptyList()
        }
    }

    /**
     * Get browse categories
     */
    suspend fun fetchCategories(limit: Int = 20): List<SpotifyCategorySummary> = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext emptyList()
        runCatching {
            SpotifyApiClient.getCategories(appContext, limit = limit)?.items?.map { category ->
                SpotifyCategorySummary(
                    id = category.id,
                    name = category.name,
                    coverUrl = category.icons.firstOrNull()?.url ?: ""
                )
            } ?: emptyList()
        }.getOrElse { err ->
            Log.w(TAG, "Failed to fetch categories: ${err.message}")
            emptyList()
        }
    }

    /**
     * Get playlists for a specific category
     */
    suspend fun fetchCategoryPlaylists(categoryId: String, limit: Int = 20): List<SpotifyPlaylistSummary> = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext emptyList()
        runCatching {
            SpotifyApiClient.getCategoryPlaylists(appContext, categoryId, limit = limit)?.items?.map { playlist ->
                SpotifyPlaylistSummary(
                    id = playlist.id,
                    name = playlist.name,
                    coverUrl = playlist.images.firstOrNull()?.url ?: "",
                    trackCount = playlist.trackCount
                )
            } ?: emptyList()
        }.getOrElse { err ->
            Log.w(TAG, "Failed to fetch category playlists: ${err.message}")
            emptyList()
        }
    }

    /**
     * Get artist's albums
     */
    suspend fun fetchArtistAlbums(artistId: String, limit: Int = 20): List<SpotifyAlbumSummary> = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext emptyList()
        runCatching {
            SpotifyApiClient.getArtistAlbums(appContext, artistId, limit = limit)?.items?.map { album ->
                SpotifyAlbumSummary(
                    id = album.id,
                    name = album.name,
                    coverUrl = album.images.firstOrNull()?.url ?: "",
                    artist = album.artists.firstOrNull()?.name ?: "Unknown Artist",
                    totalTracks = album.totalTracks,
                    releaseDate = album.releaseDate
                )
            } ?: emptyList()
        }.getOrElse { err ->
            Log.w(TAG, "Failed to fetch artist albums: ${err.message}")
            emptyList()
        }
    }

    /**
     * Get artist top tracks
     */
    suspend fun fetchArtistTopTracks(artistId: String, limit: Int = 10): List<HybridTrack> = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext emptyList()
        runCatching {
            SpotifyApiClient.getArtistTopTracks(appContext, artistId)?.mapNotNull { track ->
                val artists = track.artists.map { it.name }.joinToString(", ")
                val coverUrl = track.album?.images?.firstOrNull()?.url ?: ""
                HybridTrack(
                    canonicalId = "spotify:${track.id}",
                    rawId = track.id,
                    sourceId = config.id,
                    title = track.name,
                    artist = artists,
                    album = track.album?.name ?: "",
                    coverUrl = coverUrl,
                    durationMs = track.durationMs,
                    codecBadge = "Spotify • Hybrid",
                    isLossless = false,
                    isExplicit = track.explicit
                )
            } ?: emptyList()
        }.getOrElse { err ->
            Log.w(TAG, "Failed to fetch artist top tracks: ${err.message}")
            emptyList()
        }
    }
}

/**
 * Spotify Category Summary for UI
 */
data class SpotifyCategorySummary(
    val id: String,
    val name: String,
    val coverUrl: String
)

/**
 * Spotify Album Summary for UI
 */
data class SpotifyAlbumSummary(
    val id: String,
    val name: String,
    val coverUrl: String,
    val artist: String,
    val totalTracks: Int,
    val releaseDate: String
)

/**
 * Spotify Playlist Summary for UI
 */
data class SpotifyPlaylistSummary(
    val id: String,
    val name: String,
    val coverUrl: String,
    val trackCount: Int
)

/**
 * Registers Spotify provider with HybridSourceRegistry
 */
object SpotifyProviderRegistrar {
    private const val TAG = "KiloSpotifyRegistrar"

    fun register(context: Context) {
        HybridSourceRegistry.ensureInitialized(context)

        // The provider is already registered in HybridSourceRegistry.rebuildProviders()
        // when SOURCE_ID_SPOTIFY is in the configured sources
        // This just ensures it's initialized
        Log.d(TAG, "Spotify provider registered")
    }

    fun isSpotifyEnabled(context: Context): Boolean {
        return HybridSourceRegistry.isSpotifyEnabled(context)
    }

    fun setSpotifyEnabled(context: Context, enabled: Boolean) {
        HybridSourceRegistry.setSourceEnabled(context, HybridSourceRegistry.SOURCE_ID_SPOTIFY, enabled)
    }

    fun setSpotifyPriority(context: Context, priority: Int) {
        HybridSourceRegistry.moveSourcePriority(context, HybridSourceRegistry.SOURCE_ID_SPOTIFY, priority)
    }
}