package com.example.kilo.spotify.net

import com.example.kilo.spotify.SpotifyGraphqlClient
import com.example.kilo.spotify.SpotifyArtist
import com.example.kilo.spotify.SpotifyResult
import com.example.kilo.spotify.SpotifyAlbum
import com.example.kilo.spotify.parser.SpotifyJsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpotifyArtistApi(private val client: SpotifyGraphqlClient) {

    suspend fun getArtist(uri: String, locale: String = ""): SpotifyResult<SpotifyArtist> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getArtist",
            variables = mapOf("uri" to uri, "locale" to locale),
            responseType = ArtistResponse.serializer()
        )

        result.mapResult { response ->
            SpotifyJsonParser.parseArtistDto(response.data?.artist?.artist)
                ?: throw Exception("Failed to parse artist")
        }
    }

    suspend fun getArtistDiscography(
        uri: String,
        limit: Int = 50,
        offset: Int = 0,
        locale: String = ""
    ): SpotifyResult<List<SpotifyAlbum>> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getArtistDiscography",
            variables = mapOf("uri" to uri, "limit" to limit, "offset" to offset, "locale" to locale),
            responseType = DiscographyResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.artist?.artist?.discography?.items
                ?.mapNotNull { SpotifyJsonParser.parseAlbumDto(it.releases?.items?.firstOrNull()?.album) }
                ?: emptyList()
        }
    }

    suspend fun getArtistTopTracks(uri: String, locale: String = ""): SpotifyResult<List<SpotifyArtist>> = withContext(Dispatchers.IO) {
        SpotifyResult.Success(emptyList())
    }

    suspend fun getRelatedArtists(uri: String, locale: String = ""): SpotifyResult<List<SpotifyArtist>> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "getArtistRelatedArtists",
            variables = mapOf("uri" to uri, "locale" to locale),
            responseType = RelatedArtistsResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.artist?.artist?.relatedArtists?.items
                ?.mapNotNull { SpotifyJsonParser.parseArtistDto(it) }
                ?: emptyList()
        }
    }
}

// Response classes for serialization (top-level for kotlinx.serialization)
@kotlinx.serialization.Serializable
data class ArtistResponse(
    val data: ArtistResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val artist: ArtistUnion? = null
    ) {
        @kotlinx.serialization.Serializable
        data class ArtistUnion(
            val __typename: String = "",
            val artist: com.example.kilo.spotify.SpotifyArtistDto? = null
        )
    }
}

@kotlinx.serialization.Serializable
data class DiscographyResponse(
    val data: DiscographyResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val artist: ArtistUnion? = null
    ) {
        @kotlinx.serialization.Serializable
        data class ArtistUnion(
            val __typename: String = "",
            val artist: ArtistDiscography? = null
        ) {
            @kotlinx.serialization.Serializable
            data class ArtistDiscography(
                val discography: Discography? = null
            ) {
                @kotlinx.serialization.Serializable
                data class Discography(
                    val items: List<DiscographyItem>? = null
                ) {
                    @kotlinx.serialization.Serializable
                    data class DiscographyItem(
                        val releases: Releases? = null
                    ) {
                        @kotlinx.serialization.Serializable
                        data class Releases(
                            val items: List<ReleaseItem>? = null
                        ) {
                            @kotlinx.serialization.Serializable
                            data class ReleaseItem(
                                val album: com.example.kilo.spotify.SpotifyAlbumDto? = null
                            )
                        }
                    }
                }
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class RelatedArtistsResponse(
    val data: RelatedArtistsResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val artist: ArtistUnion? = null
    ) {
        @kotlinx.serialization.Serializable
        data class ArtistUnion(
            val __typename: String = "",
            val artist: ArtistWithRelated? = null
        ) {
            @kotlinx.serialization.Serializable
            data class ArtistWithRelated(
                val relatedArtists: RelatedArtists? = null
            ) {
                @kotlinx.serialization.Serializable
                data class RelatedArtists(
                    val items: List<com.example.kilo.spotify.SpotifyArtistDto>? = null
                )
            }
        }
    }
}