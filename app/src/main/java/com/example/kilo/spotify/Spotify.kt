package com.example.kilo.spotify

import android.content.Context
import com.example.kilo.spotify.*
import com.example.kilo.spotify.net.*
import com.example.kilo.spotify.net.AddTracksResult
import com.example.kilo.spotify.net.RemoveTracksResult
import com.example.kilo.spotify.net.ReorderTracksResult
import com.example.kilo.spotify.parser.SpotifyJsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Public Facade for Spotify API
 * Single entry point for all Spotify functionality
 * UI and playback layers never interact directly with raw DTOs, GraphQL clients, or auth tokens
 */
class Spotify(private val context: Context) {
    private val auth = SpotifyAuth(context)
    private val graphqlClient = SpotifyGraphqlClient(context, auth)
    private val albumApi = SpotifyAlbumApi(graphqlClient)
    private val artistApi = SpotifyArtistApi(graphqlClient)
    private val feedApi = SpotifyFeedApi(graphqlClient)
    private val libraryApi = SpotifyLibraryQueryApi(graphqlClient)
    private val playlistQueryApi = SpotifyPlaylistQueryApi(graphqlClient)
    private val playlistMutationApi = SpotifyPlaylistMutationApi(graphqlClient)

    // Session management
    fun isLoggedIn(): Boolean = auth.isLoggedIn()
    suspend fun getAccessToken(): String? = auth.getAccessToken()
    fun getSpDcCookie(): String? = auth.getSpDcCookie()
    fun saveWebSession(spDcCookie: String) = auth.saveWebSession(spDcCookie)
    fun logout() = auth.logout()

    // Search
    suspend fun search(
        query: String,
        types: List<String> = listOf("track", "artist", "album", "playlist"),
        limit: Int = 20,
        offset: Int = 0
    ): SpotifyResult<SpotifySearchResults> = withContext(Dispatchers.IO) {
        // Fallback to REST API for search since GraphQL search might not be available
        return@withContext searchRest(query, types, limit, offset)
    }

    private suspend fun searchRest(
        query: String,
        types: List<String>,
        limit: Int,
        offset: Int
    ): SpotifyResult<SpotifySearchResults> = withContext(Dispatchers.IO) {
        val token = auth.getAccessToken() ?: return@withContext SpotifyResult.Failure(SpotifyError(message = "No access token", status = 401))

        try {
            val typeParam = types.joinToString(",")
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = java.net.URL("https://api.spotify.com/v1/search?q=$encodedQuery&type=$typeParam&limit=$limit&offset=$offset")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 10000
            connection.readTimeout = 15000

            if (connection.responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val error = SpotifyJsonParser.parseErrorResponse(errorBody ?: "")
                    ?: SpotifyError(status = connection.responseCode, message = "Search failed")
                return@withContext SpotifyResult.Failure(error)
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val parsed = SpotifyJsonParser.parseSearchResponse(response)
                ?: return@withContext SpotifyResult.Failure(SpotifyError(message = "Failed to parse search response", status = 500))

            val tracks = parsed.tracks?.items?.mapNotNull { SpotifyJsonParser.parseTrackDto(it) } ?: emptyList()
            val artists = parsed.artists?.items?.mapNotNull { SpotifyJsonParser.parseArtistDto(it) } ?: emptyList()
            val albums = parsed.albums?.items?.mapNotNull { SpotifyJsonParser.parseAlbumDto(it) } ?: emptyList()
            val playlists = parsed.playlists?.items?.mapNotNull { SpotifyJsonParser.parsePlaylistDto(it) } ?: emptyList()

            SpotifyResult.Success(SpotifySearchResults(
                tracks = tracks,
                artists = artists,
                albums = albums,
                playlists = playlists
            ))
        } catch (e: Exception) {
            SpotifyResult.Failure(SpotifyError(message = e.message ?: "Search failed", status = 0))
        }
    }

    // Library (Read-only)
    suspend fun getLikedTracks(limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyTrack>> =
        libraryApi.getLibraryTracks(limit, offset)

    suspend fun getSavedAlbums(limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyAlbum>> =
        libraryApi.getLibraryAlbums(limit, offset)

    suspend fun getFollowedArtists(limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyArtist>> =
        libraryApi.getLibraryArtists(limit, offset)

    suspend fun getUserPlaylists(limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyPlaylist>> =
        libraryApi.getLibraryPlaylists(limit, offset)

    suspend fun checkTracksSaved(uris: List<String>): SpotifyResult<List<Boolean>> =
        libraryApi.checkTracksSaved(uris)

    suspend fun checkAlbumsSaved(uris: List<String>): SpotifyResult<List<Boolean>> =
        libraryApi.checkAlbumsSaved(uris)

    // Playlists (Read)
    suspend fun getPlaylist(uri: String, limit: Int = 100, offset: Int = 0): SpotifyResult<SpotifyPlaylist> =
        playlistQueryApi.getPlaylist(uri, limit, offset)

    suspend fun getPlaylistTracks(uri: String, limit: Int = 100, offset: Int = 0): SpotifyResult<List<SpotifyPlaylistTrack>> =
        playlistQueryApi.getPlaylistTracks(uri, limit, offset)

    suspend fun getUserPlaylists(userUri: String, limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyPlaylist>> =
        playlistQueryApi.getUserPlaylists(userUri, limit, offset)

    suspend fun searchPlaylists(query: String, limit: Int = 20, offset: Int = 0): SpotifyResult<List<SpotifyPlaylist>> =
        playlistQueryApi.searchPlaylists(query, limit, offset)

    // Playlists (Write/Mutation)
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

    // Albums
    suspend fun getAlbum(uri: String): SpotifyResult<SpotifyAlbum> =
        albumApi.getAlbum(uri)

    // Artists
    suspend fun getArtist(uri: String): SpotifyResult<SpotifyArtist> =
        artistApi.getArtist(uri)

    suspend fun getArtistDiscography(uri: String, limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyAlbum>> =
        artistApi.getArtistDiscography(uri, limit, offset)

    suspend fun getRelatedArtists(uri: String): SpotifyResult<List<SpotifyArtist>> =
        artistApi.getRelatedArtists(uri)

    // Feed / Home
    suspend fun getHomeFeed(limit: Int = 20): SpotifyResult<HomeFeed> =
        feedApi.getHomeFeed(limit)

    suspend fun getRecommendations(
        seedTracks: List<String> = emptyList(),
        seedArtists: List<String> = emptyList(),
        seedGenres: List<String> = emptyList(),
        limit: Int = 20
    ): SpotifyResult<SpotifyRecommendations> =
        feedApi.getRecommendations(seedTracks, seedArtists, seedGenres, limit)

    suspend fun getCategories(limit: Int = 50, offset: Int = 0): SpotifyResult<List<SpotifyCategory>> =
        feedApi.getCategories(limit, offset)

    suspend fun getFeaturedPlaylists(limit: Int = 20, offset: Int = 0): SpotifyResult<List<SpotifyPlaylist>> =
        feedApi.getFeaturedPlaylists(limit, offset)

    suspend fun getNewReleases(limit: Int = 20, offset: Int = 0): SpotifyResult<List<SpotifyAlbum>> =
        feedApi.getNewReleases(limit, offset)

    // Library mutations (save/remove)
    suspend fun saveTracks(uris: List<String>): SpotifyResult<Boolean> = withContext(Dispatchers.IO) {
        // Would need a mutation API
        SpotifyResult.Failure(SpotifyError(message = "Not implemented", status = 501))
    }

    suspend fun removeSavedTracks(uris: List<String>): SpotifyResult<Boolean> = withContext(Dispatchers.IO) {
        SpotifyResult.Failure(SpotifyError(message = "Not implemented", status = 501))
    }

    suspend fun saveAlbums(uris: List<String>): SpotifyResult<Boolean> = withContext(Dispatchers.IO) {
        SpotifyResult.Failure(SpotifyError(message = "Not implemented", status = 501))
    }

    suspend fun removeSavedAlbums(uris: List<String>): SpotifyResult<Boolean> = withContext(Dispatchers.IO) {
        SpotifyResult.Failure(SpotifyError(message = "Not implemented", status = 501))
    }

    suspend fun followArtist(uri: String): SpotifyResult<Boolean> = withContext(Dispatchers.IO) {
        SpotifyResult.Failure(SpotifyError(message = "Not implemented", status = 501))
    }

    suspend fun unfollowArtist(uri: String): SpotifyResult<Boolean> = withContext(Dispatchers.IO) {
        SpotifyResult.Failure(SpotifyError(message = "Not implemented", status = 501))
    }

    // Hash management
    suspend fun refreshHashes() = graphqlClient.refreshAllHashes(context)
}

data class SpotifySearchResults(
    val tracks: List<SpotifyTrack> = emptyList(),
    val artists: List<SpotifyArtist> = emptyList(),
    val albums: List<SpotifyAlbum> = emptyList(),
    val playlists: List<SpotifyPlaylist> = emptyList()
)

// Extension functions for easier usage
suspend fun Context.getSpotify(): Spotify {
    return Spotify(this)
}