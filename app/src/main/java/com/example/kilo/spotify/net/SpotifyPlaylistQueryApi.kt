package com.example.kilo.spotify.net

import com.example.kilo.spotify.SpotifyGraphqlClient
import com.example.kilo.spotify.SpotifyPlaylist
import com.example.kilo.spotify.SpotifyTrack
import com.example.kilo.spotify.SpotifyPlaylistTrack
import com.example.kilo.spotify.SpotifyResult
import com.example.kilo.spotify.parser.SpotifyJsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpotifyPlaylistQueryApi(private val client: SpotifyGraphqlClient) {

    suspend fun getPlaylist(uri: String, limit: Int = 100, offset: Int = 0): SpotifyResult<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getPlaylist",
            variables = mapOf("uri" to uri, "limit" to limit, "offset" to offset),
            responseType = PlaylistResponse.serializer()
        )

        result.mapResult { response ->
            SpotifyJsonParser.parsePlaylistDto(response.data?.playlist?.playlist)
                ?: throw Exception("Failed to parse playlist")
        }
    }

    suspend fun getPlaylistTracks(uri: String, limit: Int = 100, offset: Int = 0): SpotifyResult<List<SpotifyPlaylistTrack>> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getPlaylistTracks",
            variables = mapOf("uri" to uri, "limit" to limit, "offset" to offset),
            responseType = PlaylistTracksResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.playlist?.playlist?.contents?.items?.mapNotNull { item ->
                SpotifyJsonParser.parsePlaylistTrackItemDto(item)
            } ?: emptyList()
        }
    }

    suspend fun getUserPlaylists(userUri: String, limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyPlaylist>> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getUserPlaylists",
            variables = mapOf("uri" to userUri, "limit" to limit, "offset" to offset),
            responseType = UserPlaylistsResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.user?.playlists?.items?.mapNotNull { SpotifyJsonParser.parsePlaylistDto(it) } ?: emptyList()
        }
    }

    suspend fun searchPlaylists(query: String, limit: Int = 20, offset: Int = 0): SpotifyResult<List<SpotifyPlaylist>> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "searchPlaylists",
            variables = mapOf("query" to query, "limit" to limit, "offset" to offset),
            responseType = SearchPlaylistsResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.search?.playlists?.items?.mapNotNull { SpotifyJsonParser.parsePlaylistDto(it) } ?: emptyList()
        }
    }
}

// Response classes for serialization (top-level for kotlinx.serialization)
@kotlinx.serialization.Serializable
data class PlaylistResponse(
    val data: PlaylistResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val playlist: PlaylistUnion? = null
    ) {
        @kotlinx.serialization.Serializable
        data class PlaylistUnion(
            val __typename: String = "",
            val playlist: com.example.kilo.spotify.SpotifyPlaylistDto? = null
        )
    }
}

@kotlinx.serialization.Serializable
data class PlaylistTracksResponse(
    val data: PlaylistTracksResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val playlist: PlaylistUnion? = null
    ) {
        @kotlinx.serialization.Serializable
        data class PlaylistUnion(
            val __typename: String = "",
            val playlist: PlaylistWithContents? = null
        ) {
            @kotlinx.serialization.Serializable
            data class PlaylistWithContents(
                val contents: Contents? = null
            ) {
                @kotlinx.serialization.Serializable
                data class Contents(
                    val items: List<com.example.kilo.spotify.SpotifyPlaylistTrackItemDto>? = null
                )
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class UserPlaylistsResponse(
    val data: UserPlaylistsResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val user: User? = null
    ) {
        @kotlinx.serialization.Serializable
        data class User(
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
data class SearchPlaylistsResponse(
    val data: SearchPlaylistsResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val search: Search? = null
    ) {
        @kotlinx.serialization.Serializable
        data class Search(
            val playlists: Playlists? = null
        ) {
            @kotlinx.serialization.Serializable
            data class Playlists(
                val items: List<com.example.kilo.spotify.SpotifyPlaylistDto>? = null
            )
        }
    }
}