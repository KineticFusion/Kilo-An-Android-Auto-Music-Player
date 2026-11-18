package com.example.kilo.spotify.net

import com.example.kilo.spotify.SpotifyGraphqlClient
import com.example.kilo.spotify.SpotifyAlbum
import com.example.kilo.spotify.SpotifyResult
import com.example.kilo.spotify.parser.SpotifyJsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpotifyAlbumApi(private val client: SpotifyGraphqlClient) {

    suspend fun getAlbum(uri: String, locale: String = ""): SpotifyResult<SpotifyAlbum> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getAlbum",
            variables = mapOf("uri" to uri, "locale" to locale),
            responseType = AlbumResponse.serializer()
        )

        result.mapResult { response ->
            SpotifyJsonParser.parseAlbumDto(response.data?.album?.album)
                ?: throw Exception("Failed to parse album")
        }
    }

    suspend fun getAlbumTracks(uri: String, limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyAlbum>> = withContext(Dispatchers.IO) {
        // This would need a separate GraphQL operation
        SpotifyResult.Success(emptyList())
    }
}

// Response classes for serialization (top-level for kotlinx.serialization)
@kotlinx.serialization.Serializable
data class AlbumResponse(
    val data: AlbumResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val album: AlbumUnion? = null
    ) {
        @kotlinx.serialization.Serializable
        data class AlbumUnion(
            val __typename: String = "",
            val album: com.example.kilo.spotify.SpotifyAlbumDto? = null
        )
    }
}