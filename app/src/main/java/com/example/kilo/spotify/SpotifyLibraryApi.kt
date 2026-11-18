package com.example.kilo.spotify

import com.example.kilo.spotify.*
import com.example.kilo.spotify.net.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Facade combining LibraryQuery + PlaylistMutation/Query APIs
 * Provides high-level library management operations
 */
class SpotifyLibraryApi(
    private val libraryQueryApi: SpotifyLibraryQueryApi,
    private val playlistQueryApi: SpotifyPlaylistQueryApi,
    private val playlistMutationApi: SpotifyPlaylistMutationApi
) {

    companion object {
        @Volatile
        private var INSTANCE: SpotifyLibraryApi? = null

        fun getInstance(client: SpotifyGraphqlClient): SpotifyLibraryApi {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpotifyLibraryApi(
                    SpotifyLibraryQueryApi(client),
                    SpotifyPlaylistQueryApi(client),
                    SpotifyPlaylistMutationApi(client)
                ).also { INSTANCE = it }
            }
        }
    }

    // Library queries
    suspend fun getLikedTracks(limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyTrack>> =
        libraryQueryApi.getLibraryTracks(limit, offset)

    suspend fun getSavedAlbums(limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyAlbum>> =
        libraryQueryApi.getLibraryAlbums(limit, offset)

    suspend fun getFollowedArtists(limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyArtist>> =
        libraryQueryApi.getLibraryArtists(limit, offset)

    suspend fun getUserPlaylists(limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyPlaylist>> =
        libraryQueryApi.getLibraryPlaylists(limit, offset)

    suspend fun checkTracksSaved(uris: List<String>): SpotifyResult<List<Boolean>> =
        libraryQueryApi.checkTracksSaved(uris)

    suspend fun checkAlbumsSaved(uris: List<String>): SpotifyResult<List<Boolean>> =
        libraryQueryApi.checkAlbumsSaved(uris)

    // Playlist queries
    suspend fun getPlaylist(uri: String, limit: Int = 100, offset: Int = 0): SpotifyResult<SpotifyPlaylist> =
        playlistQueryApi.getPlaylist(uri, limit, offset)

    suspend fun getPlaylistTracks(uri: String, limit: Int = 100, offset: Int = 0): SpotifyResult<List<SpotifyPlaylistTrack>> =
        playlistQueryApi.getPlaylistTracks(uri, limit, offset)

    suspend fun searchPlaylists(query: String, limit: Int = 20, offset: Int = 0): SpotifyResult<List<SpotifyPlaylist>> =
        playlistQueryApi.searchPlaylists(query, limit, offset)

    // Playlist mutations
    suspend fun createPlaylist(
        name: String,
        isPublic: Boolean = false,
        isCollaborative: Boolean = false,
        description: String = ""
    ): SpotifyResult<SpotifyPlaylist> =
        playlistMutationApi.createPlaylist(name, isPublic, isCollaborative, description)

    suspend fun addTracksToPlaylist(
        playlistUri: String,
        trackUris: List<String>,
        position: Int? = null
    ): SpotifyResult<AddTracksResult> =
        playlistMutationApi.addTracksToPlaylist(playlistUri, trackUris, position)

    suspend fun removeTracksFromPlaylist(
        playlistUri: String,
        trackUris: List<String>
    ): SpotifyResult<RemoveTracksResult> =
        playlistMutationApi.removeTracksFromPlaylist(playlistUri, trackUris)

    suspend fun reorderPlaylistTracks(
        playlistUri: String,
        rangeStart: Int,
        insertBefore: Int,
        rangeLength: Int = 1,
        snapshotId: String? = null
    ): SpotifyResult<ReorderTracksResult> =
        playlistMutationApi.reorderPlaylistTracks(playlistUri, rangeStart, insertBefore, rangeLength, snapshotId)

    suspend fun updatePlaylistDetails(
        playlistUri: String,
        name: String? = null,
        isPublic: Boolean? = null,
        isCollaborative: Boolean? = null,
        description: String? = null
    ): SpotifyResult<SpotifyPlaylist> =
        playlistMutationApi.updatePlaylistDetails(playlistUri, name, isPublic, isCollaborative, description)

    suspend fun deletePlaylist(playlistUri: String): SpotifyResult<Boolean> =
        playlistMutationApi.deletePlaylist(playlistUri)

    suspend fun followPlaylist(playlistUri: String, isPublic: Boolean = false): SpotifyResult<Boolean> =
        playlistMutationApi.followPlaylist(playlistUri, isPublic)

    suspend fun unfollowPlaylist(playlistUri: String): SpotifyResult<Boolean> =
        playlistMutationApi.unfollowPlaylist(playlistUri)
}