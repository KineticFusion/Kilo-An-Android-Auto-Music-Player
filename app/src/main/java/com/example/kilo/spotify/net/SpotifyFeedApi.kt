package com.example.kilo.spotify.net

import com.example.kilo.spotify.SpotifyGraphqlClient
import com.example.kilo.spotify.SpotifyTrack
import com.example.kilo.spotify.SpotifyPlaylist
import com.example.kilo.spotify.SpotifyAlbum
import com.example.kilo.spotify.SpotifyResult
import com.example.kilo.spotify.SpotifyRecommendations
import com.example.kilo.spotify.SpotifyCategory
import com.example.kilo.spotify.parser.SpotifyJsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpotifyFeedApi(private val client: SpotifyGraphqlClient) {

    suspend fun getHomeFeed(limit: Int = 20): SpotifyResult<HomeFeed> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getHomeFeed",
            variables = mapOf("limit" to limit),
            responseType = HomeFeedResponse.serializer()
        )

        result.mapResult { response ->
            parseHomeFeed(response)
        }
    }

    suspend fun getRecommendations(
        seedTracks: List<String> = emptyList(),
        seedArtists: List<String> = emptyList(),
        seedGenres: List<String> = emptyList(),
        limit: Int = 20
    ): SpotifyResult<SpotifyRecommendations> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getRecommendations",
            variables = mapOf(
                "seedTracks" to seedTracks,
                "seedArtists" to seedArtists,
                "seedGenres" to seedGenres,
                "limit" to limit
            ),
            responseType = RecommendationsResponse.serializer()
        )

        result.mapResult { response ->
            val tracks = response.data?.recommendations?.tracks?.items?.mapNotNull { SpotifyJsonParser.parseTrackDto(it.track) } ?: emptyList()
            val seeds = response.data?.recommendations?.seeds?.mapNotNull { SpotifyJsonParser.parseRecommendationSeedDto(it) } ?: emptyList()
            SpotifyRecommendations(tracks = tracks, seeds = seeds)
        }
    }

    suspend fun getCategories(limit: Int = 50, offset: Int = 0, locale: String = ""): SpotifyResult<List<SpotifyCategory>> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getCategories",
            variables = mapOf("limit" to limit, "offset" to offset, "locale" to locale),
            responseType = CategoriesResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.categories?.items?.mapNotNull { SpotifyJsonParser.parseCategoryDto(it) } ?: emptyList()
        }
    }

    suspend fun getFeaturedPlaylists(limit: Int = 20, offset: Int = 0, locale: String = ""): SpotifyResult<List<SpotifyPlaylist>> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getFeaturedPlaylists",
            variables = mapOf("limit" to limit, "offset" to offset, "locale" to locale),
            responseType = FeaturedPlaylistsResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.featuredPlaylists?.playlists?.items?.mapNotNull { SpotifyJsonParser.parsePlaylistDto(it) } ?: emptyList()
        }
    }

    suspend fun getNewReleases(limit: Int = 20, offset: Int = 0, locale: String = ""): SpotifyResult<List<SpotifyAlbum>> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getNewReleases",
            variables = mapOf("limit" to limit, "offset" to offset, "locale" to locale),
            responseType = NewReleasesResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.newReleases?.albums?.items?.mapNotNull { SpotifyJsonParser.parseAlbumDto(it) } ?: emptyList()
        }
    }

    private fun parseHomeFeed(response: HomeFeedResponse): HomeFeed {
        val shelves = response.data?.home?.shelves?.items?.mapNotNull { shelf ->
            val items = shelf.content?.items?.mapNotNull { item ->
                when (item.__typename) {
                    "Playlist" -> SpotifyJsonParser.parsePlaylistDto(item.playlist)
                    "Album" -> SpotifyJsonParser.parseAlbumDto(item.album)
                    "Artist" -> SpotifyJsonParser.parseArtistDto(item.artist)
                    "Track" -> SpotifyJsonParser.parseTrackDto(item.track)
                    else -> null
                }
            } ?: emptyList()

            shelf.title?.let { title ->
                Shelf(title = title, items = items)
            }
        } ?: emptyList()

        return HomeFeed(shelves = shelves)
    }
}

// Response classes for serialization (top-level for kotlinx.serialization)
data class HomeFeed(
    val shelves: List<Shelf> = emptyList()
)

data class Shelf(
    val title: String,
    val items: List<Any> = emptyList()
)

@kotlinx.serialization.Serializable
data class HomeFeedResponse(
    val data: HomeFeedResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val home: Home? = null
    ) {
        @kotlinx.serialization.Serializable
        data class Home(
            val shelves: Shelves? = null
        ) {
            @kotlinx.serialization.Serializable
            data class Shelves(
                val items: List<ShelfItem>? = null
            ) {
                @kotlinx.serialization.Serializable
                data class ShelfItem(
                    val title: String? = null,
                    val content: Content? = null
                ) {
                    @kotlinx.serialization.Serializable
                    data class Content(
                        val items: List<ContentItem>? = null
                    ) {
                        @kotlinx.serialization.Serializable
                        data class ContentItem(
                            val __typename: String = "",
                            val playlist: com.example.kilo.spotify.SpotifyPlaylistDto? = null,
                            val album: com.example.kilo.spotify.SpotifyAlbumDto? = null,
                            val artist: com.example.kilo.spotify.SpotifyArtistDto? = null,
                            val track: com.example.kilo.spotify.SpotifyTrackDto? = null
                        )
                    }
                }
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class RecommendationsResponse(
    val data: RecommendationsResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val recommendations: Recommendations? = null
    ) {
        @kotlinx.serialization.Serializable
        data class Recommendations(
            val tracks: Tracks? = null,
            val seeds: List<com.example.kilo.spotify.SpotifyRecommendationSeedDto>? = null
        ) {
            @kotlinx.serialization.Serializable
            data class Tracks(
                val items: List<TrackItem>? = null
            ) {
                @kotlinx.serialization.Serializable
                data class TrackItem(
                    val track: com.example.kilo.spotify.SpotifyTrackDto? = null
                )
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class CategoriesResponse(
    val data: CategoriesResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val categories: Categories? = null
    ) {
        @kotlinx.serialization.Serializable
        data class Categories(
            val items: List<com.example.kilo.spotify.SpotifyCategoryDto>? = null
        )
    }
}

@kotlinx.serialization.Serializable
data class FeaturedPlaylistsResponse(
    val data: FeaturedPlaylistsResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val featuredPlaylists: FeaturedPlaylists? = null
    ) {
        @kotlinx.serialization.Serializable
        data class FeaturedPlaylists(
            val playlists: Playlists? = null
        ) {
            @kotlinx.serialization.Serializable
            data class Playlists(
                val items: List<com.example.kilo.spotify.SpotifyPlaylistDto>? = null
            )
        }
    }
}

@kotlinx.serialization.Serializable
data class NewReleasesResponse(
    val data: NewReleasesResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val newReleases: NewReleases? = null
    ) {
        @kotlinx.serialization.Serializable
        data class NewReleases(
            val albums: Albums? = null
        ) {
            @kotlinx.serialization.Serializable
            data class Albums(
                val items: List<com.example.kilo.spotify.SpotifyAlbumDto>? = null
            )
        }
    }
}