package com.example.kilo.spotify.net

import com.example.kilo.spotify.SpotifyGraphqlClient
import com.example.kilo.spotify.SpotifyPlaylist
import com.example.kilo.spotify.SpotifyResult
import com.example.kilo.spotify.parser.SpotifyJsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpotifyPlaylistMutationApi(private val client: SpotifyGraphqlClient) {

    suspend fun createPlaylist(
        name: String,
        isPublic: Boolean = false,
        isCollaborative: Boolean = false,
        description: String = ""
    ): SpotifyResult<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "createPlaylist",
            variables = mapOf(
                "name" to name,
                "public" to isPublic,
                "collaborative" to isCollaborative,
                "description" to description
            ),
            responseType = CreatePlaylistResponse.serializer()
        )

        result.mapResult { response ->
            SpotifyJsonParser.parsePlaylistDto(response.data?.createPlaylist?.playlist)
                ?: throw Exception("Failed to parse created playlist")
        }
    }

    suspend fun addTracksToPlaylist(
        playlistUri: String,
        trackUris: List<String>,
        position: Int? = null
    ): SpotifyResult<AddTracksResult> = withContext(Dispatchers.IO) {
        val variables = mutableMapOf<String, Any>(
            "playlistUri" to playlistUri,
            "uris" to trackUris
        )
        position?.let { variables["position"] = it }

        val result = client.executeGraphQL(
            operationName = "addTracksToPlaylist",
            variables = variables,
            responseType = AddTracksResponse.serializer()
        )

        result.mapResult { response ->
            AddTracksResult(
                snapshotId = response.data?.addTracksToPlaylist?.snapshotId ?: ""
            )
        }
    }

    suspend fun removeTracksFromPlaylist(
        playlistUri: String,
        trackUris: List<String>
    ): SpotifyResult<RemoveTracksResult> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "removeTracksFromPlaylist",
            variables = mapOf(
                "playlistUri" to playlistUri,
                "uris" to trackUris
            ),
            responseType = RemoveTracksResponse.serializer()
        )

        result.mapResult { response ->
            RemoveTracksResult(
                snapshotId = response.data?.removeTracksFromPlaylist?.snapshotId ?: ""
            )
        }
    }

    suspend fun reorderPlaylistTracks(
        playlistUri: String,
        rangeStart: Int,
        insertBefore: Int,
        rangeLength: Int = 1,
        snapshotId: String? = null
    ): SpotifyResult<ReorderTracksResult> = withContext(Dispatchers.IO) {
        val variables = mutableMapOf<String, Any>(
            "playlistUri" to playlistUri,
            "rangeStart" to rangeStart,
            "insertBefore" to insertBefore,
            "rangeLength" to rangeLength
        )
        snapshotId?.let { variables["snapshotId"] = it }

        val result = client.executeGraphQL(
            operationName = "reorderPlaylistTracks",
            variables = variables,
            responseType = ReorderTracksResponse.serializer()
        )

        result.mapResult { response ->
            ReorderTracksResult(
                snapshotId = response.data?.reorderPlaylistTracks?.snapshotId ?: ""
            )
        }
    }

    suspend fun updatePlaylistDetails(
        playlistUri: String,
        name: String? = null,
        isPublic: Boolean? = null,
        isCollaborative: Boolean? = null,
        description: String? = null
    ): SpotifyResult<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val variables = mutableMapOf<String, Any>("playlistUri" to playlistUri)
        name?.let { variables["name"] = it }
        isPublic?.let { variables["public"] = it }
        isCollaborative?.let { variables["collaborative"] = it }
        description?.let { variables["description"] = it }

        val result = client.executeGraphQL(
            operationName = "updatePlaylistDetails",
            variables = variables,
            responseType = UpdatePlaylistResponse.serializer()
        )

        result.mapResult { response ->
            SpotifyJsonParser.parsePlaylistDto(response.data?.updatePlaylist?.playlist)
                ?: throw Exception("Failed to parse updated playlist")
        }
    }

    suspend fun deletePlaylist(playlistUri: String): SpotifyResult<Boolean> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "deletePlaylist",
            variables = mapOf("playlistUri" to playlistUri),
            responseType = DeletePlaylistResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.deletePlaylist?.success == true
        }
    }

    suspend fun followPlaylist(playlistUri: String, isPublic: Boolean = false): SpotifyResult<Boolean> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "followPlaylist",
            variables = mapOf("playlistUri" to playlistUri, "public" to isPublic),
            responseType = FollowPlaylistResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.followPlaylist?.success == true
        }
    }

    suspend fun unfollowPlaylist(playlistUri: String): SpotifyResult<Boolean> = withContext(Dispatchers.IO) {
        val result = client.executeGraphQL(
            operationName = "unfollowPlaylist",
            variables = mapOf("playlistUri" to playlistUri),
            responseType = UnfollowPlaylistResponse.serializer()
        )

        result.mapResult { response ->
            response.data?.unfollowPlaylist?.success == true
        }
    }

    companion object {
    }
}

// Response classes for serialization (top-level for kotlinx.serialization)
@kotlinx.serialization.Serializable
data class CreatePlaylistResponse(
    val data: CreatePlaylistResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val createPlaylist: CreatePlaylist? = null
    ) {
        @kotlinx.serialization.Serializable
        data class CreatePlaylist(
            val playlist: com.example.kilo.spotify.SpotifyPlaylistDto? = null
        )
    }
}

@kotlinx.serialization.Serializable
data class AddTracksResponse(
    val data: AddTracksResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val addTracksToPlaylist: AddTracks? = null
    ) {
        @kotlinx.serialization.Serializable
        data class AddTracks(
            val snapshotId: String = ""
        )
    }
}

@kotlinx.serialization.Serializable
data class RemoveTracksResponse(
    val data: RemoveTracksResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val removeTracksFromPlaylist: RemoveTracks? = null
    ) {
        @kotlinx.serialization.Serializable
        data class RemoveTracks(
            val snapshotId: String = ""
        )
    }
}

@kotlinx.serialization.Serializable
data class ReorderTracksResponse(
    val data: ReorderTracksResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val reorderPlaylistTracks: ReorderTracks? = null
    ) {
        @kotlinx.serialization.Serializable
        data class ReorderTracks(
            val snapshotId: String = ""
        )
    }
}

@kotlinx.serialization.Serializable
data class UpdatePlaylistResponse(
    val data: UpdatePlaylistResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val updatePlaylist: UpdatePlaylist? = null
    ) {
        @kotlinx.serialization.Serializable
        data class UpdatePlaylist(
            val playlist: com.example.kilo.spotify.SpotifyPlaylistDto? = null
        )
    }
}

@kotlinx.serialization.Serializable
data class DeletePlaylistResponse(
    val data: DeletePlaylistResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val deletePlaylist: DeletePlaylist? = null
    ) {
        @kotlinx.serialization.Serializable
        data class DeletePlaylist(
            val success: Boolean = false
        )
    }
}

@kotlinx.serialization.Serializable
data class FollowPlaylistResponse(
    val data: FollowPlaylistResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val followPlaylist: FollowPlaylist? = null
    ) {
        @kotlinx.serialization.Serializable
        data class FollowPlaylist(
            val success: Boolean = false
        )
    }
}

@kotlinx.serialization.Serializable
data class UnfollowPlaylistResponse(
    val data: UnfollowPlaylistResponse.Data? = null
) {
    @kotlinx.serialization.Serializable
    data class Data(
        val unfollowPlaylist: UnfollowPlaylist? = null
    ) {
        @kotlinx.serialization.Serializable
        data class UnfollowPlaylist(
            val success: Boolean = false
        )
    }
}

// Result classes for mutations (top-level for easy access)
data class AddTracksResult(val snapshotId: String)
data class RemoveTracksResult(val snapshotId: String)
data class ReorderTracksResult(val snapshotId: String)