package com.example.kilo.spotify.net

import com.example.kilo.spotify.SpotifyGraphqlClient
import com.example.kilo.spotify.SpotifyTrack
import com.example.kilo.spotify.SpotifyAlbum
import com.example.kilo.spotify.SpotifyArtist
import com.example.kilo.spotify.SpotifyPlaylist
import com.example.kilo.spotify.SpotifyResult
import com.example.kilo.spotify.parser.SpotifyJsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpotifyLibraryQueryApi(private val client: SpotifyGraphqlClient) {

    suspend fun getLibraryTracks(limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyTrack>> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getLibraryTracks",
            variables = mapOf("limit" to limit, "offset" to offset),
            responseType = LibraryTracksResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.me?.tracks?.items?.mapNotNull { item ->
                SpotifyJsonParser.parseSavedTrackDto(item)
            } ?: emptyList()
        }
    }

    suspend fun getLibraryAlbums(limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyAlbum>> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getLibraryAlbums",
            variables = mapOf("limit" to limit, "offset" to offset),
            responseType = LibraryAlbumsResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.me?.albums?.items?.mapNotNull { item ->
                SpotifyJsonParser.parseSavedAlbumDto(item)
            } ?: emptyList()
        }
    }

    suspend fun getLibraryArtists(limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyArtist>> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getLibraryArtists",
            variables = mapOf("limit" to limit, "offset" to offset),
            responseType = LibraryArtistsResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.me?.following?.artists?.items?.mapNotNull { item ->
                SpotifyJsonParser.parseArtistDto(item.artist)
            } ?: emptyList()
        }
    }

    suspend fun getLibraryPlaylists(limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyPlaylist>> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getLibraryPlaylists",
            variables = mapOf("limit" to limit, "offset" to offset),
            responseType = LibraryPlaylistsResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.me?.playlists?.items?.mapNotNull { SpotifyJsonParser.parsePlaylistDto(it) } ?: emptyList()
        }
    }

    suspend fun checkTracksSaved(uris: List<String>): SpotifyResult<List<Boolean>> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "checkTracksSaved",
            variables = mapOf("uris" to uris),
            responseType = CheckSavedResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.me?.tracks?.saved?.map { it.saved } ?: uris.map { false }
        }
    }

    suspend fun checkAlbumsSaved(uris: List<String>): SpotifyResult<List<Boolean>> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "checkAlbumsSaved",
            variables = mapOf("uris" to uris),
            responseType = CheckAlbumsSavedResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.me?.albums?.saved?.map { it.saved } ?: uris.map { false }
        }
    }
}

// Response classes for serialization (top-level for kotlinx.serialization)
@kotlinx.serialization.Serializable
data class LibraryTracksResponse(
    val data: LibraryTracksResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val me: Me? = null
    ) {
        @kotlinx.serialization.Serializable
        data class Me(
            val tracks: Tracks? = null
        ) {
            @kotlinx.serialization.Serializable
            data class Tracks(
                val items: List<com.example.kilo.spotify.SpotifySavedTrackDto>? = null
            )
        }
    }
}

@kotlinx.serialization.Serializable
data class LibraryAlbumsResponse(
    val data: LibraryAlbumsResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val me: Me? = null
    ) {
        @kotlinx.serialization.Serializable
        data class Me(
            val albums: Albums? = null
        ) {
            @kotlinx.serialization.Serializable
            data class Albums(
                val items: List<com.example.kilo.spotify.SpotifySavedAlbumDto>? = null
            )
        }
    }
}

@kotlinx.serialization.Serializable
data class LibraryArtistsResponse(
    val data: LibraryArtistsResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val me: Me? = null
    ) {
        @kotlinx.serialization.Serializable
        data class Me(
            val following: Following? = null
        ) {
            @kotlinx.serialization.Serializable
            data class Following(
                val artists: Artists? = null
            ) {
                @kotlinx.serialization.Serializable
                data class Artists(
                    val items: List<ArtistItem>? = null
                ) {
                    @kotlinx.serialization.Serializable
                    data class ArtistItem(
                        val artist: com.example.kilo.spotify.SpotifyArtistDto? = null
                    )
                }
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class LibraryPlaylistsResponse(
    val data: LibraryPlaylistsResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val me: Me? = null
    ) {
        @kotlinx.serialization.Serializable
        data class Me(
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
data class CheckSavedResponse(
    val data: CheckSavedResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val me: Me? = null
    ) {
        @kotlinx.serialization.Serializable
        data class Me(
            val tracks: Tracks? = null
        ) {
            @kotlinx.serialization.Serializable
            data class Tracks(
                val saved: List<SavedTrack>? = null
            ) {
                @kotlinx.serialization.Serializable
                data class SavedTrack(
                    val saved: Boolean = false
                )
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class CheckAlbumsSavedResponse(
    val data: CheckAlbumsSavedResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val me: Me? = null
    ) {
        @kotlinx.serialization.Serializable
        data class Me(
            val albums: Albums? = null
        ) {
            @kotlinx.serialization.Serializable
            data class Albums(
                val saved: List<SavedAlbum>? = null
            ) {
                @kotlinx.serialization.Serializable
                data class SavedAlbum(
                    val saved: Boolean = false
                )
            }
        }
    }
}