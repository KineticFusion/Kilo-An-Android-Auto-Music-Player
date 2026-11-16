package com.example.kilo.spotify

import android.content.Context
import android.util.Log
import com.example.kilo.streaming.HybridSourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Spotify API Client for GraphQL and REST operations
 * Uses the web access token from HybridSourceRegistry
 */
object SpotifyApiClient {
    private const val TAG = "KiloSpotifyApi"
    private const val API_BASE = "https://api.spotify.com/v1"
    private const val GRAPHQL_URL = "https://api-partner.spotify.com/pathfinder/v1/query"

    // GraphQL operation names (from Myspoti's gql-ops.json)
    private const val OP_SEARCH = "search"
    private const val OP_GET_TRACK = "getTrack"
    private const val OP_GET_ALBUM = "getAlbum"
    private const val OP_GET_ARTIST = "getArtist"
    private const val OP_GET_PLAYLIST = "getPlaylist"
    private const val OP_GET_USER_PLAYLISTS = "getUserPlaylists"
    private const val OP_GET_LIKED_TRACKS = "getLikedTracks"
    private const val OP_GET_HOME_FEED = "getHomeFeed"
    private const val OP_GET_RECOMMENDATIONS = "getRecommendations"

    /**
     * Search for tracks, artists, albums, playlists
     */
    suspend fun search(
        context: Context,
        query: String,
        types: List<String> = listOf("track", "artist", "album", "playlist"),
        limit: Int = 20,
        offset: Int = 0
    ): SpotifySearchResult? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.getSpotifyWebSession(context).second
            ?: return@withContext null

        val typeParam = types.joinToString(",")
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$API_BASE/search?q=$encodedQuery&type=$typeParam&limit=$limit&offset=$offset"

        return@withContext makeGetRequest(url, token)?.let { parseSearchResult(it) }
    }

    /**
     * Get user's liked tracks
     */
    suspend fun getLikedTracks(context: Context, limit: Int = 50, offset: Int = 0): List<SpotifyTrack>? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.getSpotifyWebSession(context).second
            ?: return@withContext null

        val url = "$API_BASE/me/tracks?limit=$limit&offset=$offset"
        makeGetRequest(url, token)?.let { json ->
            val items = json.optJSONArray("items")
            return@withContext parseSavedTrackArray(items)
        }
    }

    /**
     * Get user's playlists
     */
    suspend fun getUserPlaylists(context: Context, limit: Int = 50, offset: Int = 0): List<SpotifyPlaylist>? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.ensureValidSpotifyWebToken(context)
            ?: return@withContext null

        val url = "$API_BASE/me/playlists?limit=$limit&offset=$offset"
        makeGetRequest(url, token)?.let { json ->
            val items = json.optJSONArray("items")
            return@withContext parsePlaylistArray(items)
        }
    }

    /**
     * Get playlist tracks
     */
    suspend fun getPlaylistTracks(context: Context, playlistId: String, limit: Int = 100, offset: Int = 0): List<SpotifyTrack>? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.ensureValidSpotifyWebToken(context)
            ?: return@withContext null

        val url = "$API_BASE/playlists/$playlistId/tracks?limit=$limit&offset=$offset"
        makeGetRequest(url, token)?.let { json ->
            val items = json.optJSONArray("items")
            return@withContext parsePlaylistTrackArray(items)
        }
    }

    /**
     * Get track details
     */
    suspend fun getTrack(context: Context, trackId: String): SpotifyTrack? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.ensureValidSpotifyWebToken(context)
            ?: return@withContext null

        val url = "$API_BASE/tracks/$trackId"
        makeGetRequest(url, token)?.let { parseTrack(it) }
    }

    /**
     * Get album details
     */
    suspend fun getAlbum(context: Context, albumId: String): SpotifyAlbum? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.ensureValidSpotifyWebToken(context)
            ?: return@withContext null

        val url = "$API_BASE/albums/$albumId"
        makeGetRequest(url, token)?.let { parseAlbum(it) }
    }

    /**
     * Get artist details
     */
    suspend fun getArtist(context: Context, artistId: String): SpotifyArtist? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.ensureValidSpotifyWebToken(context)
            ?: return@withContext null

        val url = "$API_BASE/artists/$artistId"
        makeGetRequest(url, token)?.let { parseArtist(it) }
    }

    /**
     * Get artist's top tracks
     */
    suspend fun getArtistTopTracks(context: Context, artistId: String, country: String = "US"): List<SpotifyTrack>? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.ensureValidSpotifyWebToken(context)
            ?: return@withContext null

        val url = "$API_BASE/artists/$artistId/top-tracks?country=$country"
        makeGetRequest(url, token)?.let { json ->
            val tracks = json.optJSONArray("tracks")
            return@withContext parseTrackArray(tracks)
        }
    }

    /**
     * Get user profile
     */
    suspend fun getCurrentUser(context: Context): SpotifyUser? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.ensureValidSpotifyWebToken(context)
            ?: return@withContext null

        val url = "$API_BASE/me"
        makeGetRequest(url, token)?.let { parseUser(it) }
    }

    /**
     * Get new releases
     */
    suspend fun getNewReleases(context: Context, limit: Int = 20, offset: Int = 0, country: String = "US"): SpotifyPage<SpotifyAlbum>? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.ensureValidSpotifyWebToken(context)
            ?: return@withContext null

        val url = "$API_BASE/browse/new-releases?limit=$limit&offset=$offset&country=$country"
        makeGetRequest(url, token)?.let { json ->
            val albums = json.optJSONObject("albums")
            return@withContext parseAlbumPage(albums!!)
        }
    }

    /**
     * Get featured playlists
     */
    suspend fun getFeaturedPlaylists(context: Context, limit: Int = 20, offset: Int = 0, country: String = "US", locale: String = "en_US"): SpotifyPage<SpotifyPlaylist>? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.getSpotifyWebSession(context).second
            ?: return@withContext null

        val url = "$API_BASE/browse/featured-playlists?limit=$limit&offset=$offset&country=$country&locale=$locale"
        makeGetRequest(url, token)?.let { json ->
            val playlists = json.optJSONObject("playlists")
            return@withContext parsePlaylistPage(playlists!!)
        }
    }

    /**
     * Get browse categories
     */
    suspend fun getCategories(context: Context, limit: Int = 20, offset: Int = 0, country: String = "US", locale: String = "en_US"): SpotifyPage<SpotifyCategory>? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.getSpotifyWebSession(context).second
            ?: return@withContext null

        val url = "$API_BASE/browse/categories?limit=$limit&offset=$offset&country=$country&locale=$locale"
        makeGetRequest(url, token)?.let { json ->
            val categories = json.optJSONObject("categories")
            return@withContext parseCategoryPage(categories!!)
        }
    }

    /**
     * Get category playlists
     */
    suspend fun getCategoryPlaylists(context: Context, categoryId: String, limit: Int = 20, offset: Int = 0, country: String = "US"): SpotifyPage<SpotifyPlaylist>? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.getSpotifyWebSession(context).second
            ?: return@withContext null

        val url = "$API_BASE/browse/categories/$categoryId/playlists?limit=$limit&offset=$offset&country=$country"
        makeGetRequest(url, token)?.let { json ->
            val playlists = json.optJSONObject("playlists")
            return@withContext parsePlaylistPage(playlists!!)
        }
    }

    /**
     * Get artist's albums
     */
    suspend fun getArtistAlbums(context: Context, artistId: String, limit: Int = 20, offset: Int = 0, includeGroups: String = "album,single,appears_on,compilation"): SpotifyPage<SpotifyAlbum>? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.getSpotifyWebSession(context).second
            ?: return@withContext null

        val url = "$API_BASE/artists/$artistId/albums?limit=$limit&offset=$offset&include_groups=$includeGroups"
        makeGetRequest(url, token)?.let { json ->
            val albums = json.optJSONObject("albums")
            return@withContext parseAlbumPage(albums!!)
        }
    }

    /**
     * Get recommendations based on seed tracks/artists/genres
     */
    suspend fun getRecommendations(
        context: Context,
        seedTracks: List<String> = emptyList(),
        seedArtists: List<String> = emptyList(),
        seedGenres: List<String> = emptyList(),
        limit: Int = 20
    ): List<SpotifyTrack>? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.getSpotifyWebSession(context).second
            ?: return@withContext null

        val params = mutableMapOf<String, String>()
        if (seedTracks.isNotEmpty()) params["seed_tracks"] = seedTracks.joinToString(",")
        if (seedArtists.isNotEmpty()) params["seed_artists"] = seedArtists.joinToString(",")
        if (seedGenres.isNotEmpty()) params["seed_genres"] = seedGenres.joinToString(",")
        params["limit"] = limit.toString()

        val url = "$API_BASE/recommendations?${params.map { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }.joinToString("&")}"
        makeGetRequest(url, token)?.let { json ->
            val tracks = json.optJSONArray("tracks")
            return@withContext parseTrackArray(tracks)
        }
    }

    /**
     * GraphQL query for complex operations
     */
    private suspend fun graphqlQuery(
        context: Context,
        operationName: String,
        variables: JSONObject,
        extensions: JSONObject? = null
    ): JSONObject? = withContext(Dispatchers.IO) {
        val token = HybridSourceRegistry.getSpotifyWebSession(context).second
            ?: return@withContext null

        val body = JSONObject().apply {
            put("operationName", operationName)
            put("variables", variables)
            extensions?.let { put("extensions", it) }
        }

        val url = URL(GRAPHQL_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("x-spotify-client-token", getClientToken())

        try {
            conn.outputStream.write(body.toString().toByteArray())
            conn.outputStream.close()

            val responseCode = conn.responseCode
            val responseText = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }

            if (responseCode !in 200..299) {
                Log.w(TAG, "GraphQL $operationName failed: $responseCode - ${responseText.take(500)}")
                return@withContext null
            }

            JSONObject(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "GraphQL $operationName error", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun getClientToken(): String {
        // This would be the Spotify client token minted by the app
        // For now, return empty - the web token should suffice for most operations
        return ""
    }

    private fun makeGetRequest(url: String, token: String): JSONObject? {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")

            val responseCode = conn.responseCode
            val responseText = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }

            if (responseCode !in 200..299) {
                Log.w(TAG, "GET $url failed: $responseCode - ${responseText.take(500)}")
                return null
            }

            return JSONObject(responseText)
        } catch (e: Exception) {
            Log.e(TAG, "GET $url error", e)
            return null
        }
    }

    // Parsing functions
    private fun parseSearchResult(json: JSONObject): SpotifySearchResult {
        return SpotifySearchResult(
            tracks = json.optJSONObject("tracks")?.let { parseTrackPage(it) },
            artists = json.optJSONObject("artists")?.let { parseArtistPage(it) },
            albums = json.optJSONObject("albums")?.let { parseAlbumPage(it) },
            playlists = json.optJSONObject("playlists")?.let { parsePlaylistPage(it) }
        )
    }

    private fun parseTrackPage(json: JSONObject): SpotifyPage<SpotifyTrack> {
        return SpotifyPage(
            items = parseTrackArray(json.optJSONArray("items")) ?: emptyList(),
            total = json.optInt("total"),
            limit = json.optInt("limit"),
            offset = json.optInt("offset"),
            next = json.optString("next").takeIf { it.isNotBlank() },
            previous = json.optString("previous").takeIf { it.isNotBlank() }
        )
    }

    private fun parseArtistPage(json: JSONObject): SpotifyPage<SpotifyArtist> {
        return SpotifyPage(
            items = parseArtistArray(json.optJSONArray("items")) ?: emptyList(),
            total = json.optInt("total"),
            limit = json.optInt("limit"),
            offset = json.optInt("offset"),
            next = json.optString("next").takeIf { it.isNotBlank() },
            previous = json.optString("previous").takeIf { it.isNotBlank() }
        )
    }

    private fun parseAlbumPage(json: JSONObject): SpotifyPage<SpotifyAlbum> {
        return SpotifyPage(
            items = parseAlbumArray(json.optJSONArray("items")) ?: emptyList(),
            total = json.optInt("total"),
            limit = json.optInt("limit"),
            offset = json.optInt("offset"),
            next = json.optString("next").takeIf { it.isNotBlank() },
            previous = json.optString("previous").takeIf { it.isNotBlank() }
        )
    }

    private fun parsePlaylistPage(json: JSONObject): SpotifyPage<SpotifyPlaylist> {
        return SpotifyPage(
            items = parsePlaylistArray(json.optJSONArray("items")) ?: emptyList(),
            total = json.optInt("total"),
            limit = json.optInt("limit"),
            offset = json.optInt("offset"),
            next = json.optString("next").takeIf { it.isNotBlank() },
            previous = json.optString("previous").takeIf { it.isNotBlank() }
        )
    }

    private fun parseTrackArray(json: JSONArray?): List<SpotifyTrack>? {
        return json?.let { (0 until it.length()).mapNotNull { index -> parseTrack(it.optJSONObject(index)) } }
    }

    private fun parseTrack(json: JSONObject?): SpotifyTrack? {
        return json?.let {
            SpotifyTrack(
                id = it.optString("id"),
                uri = it.optString("uri"),
                name = it.optString("name"),
                artists = parseArtistArray(it.optJSONArray("artists")) ?: emptyList(),
                album = parseAlbum(it.optJSONObject("album")),
                durationMs = it.optLong("duration_ms"),
                explicit = it.optBoolean("explicit"),
                popularity = it.optInt("popularity"),
                previewUrl = it.optString("preview_url").takeIf { it.isNotBlank() },
                externalUrls = parseExternalUrls(it.optJSONObject("external_urls")),
                images = parseImages(it.optJSONArray("images")),
                isPlayable = it.optBoolean("is_playable", true),
                trackNumber = it.optInt("track_number"),
                discNumber = it.optInt("disc_number"),
                type = it.optString("type"),
                href = it.optString("href")
            )
        }
    }

    private fun parseSavedTrackArray(json: JSONArray?): List<SpotifyTrack>? {
        return json?.let { (0 until it.length()).mapNotNull { index ->
            it.optJSONObject(index)?.optJSONObject("track")?.let { parseTrack(it) }
        } }
    }

    private fun parsePlaylistTrackArray(json: JSONArray?): List<SpotifyTrack>? {
        return json?.let { (0 until it.length()).mapNotNull { index ->
            it.optJSONObject(index)?.optJSONObject("track")?.let { parseTrack(it) }
        } }
    }

    private fun parseArtistArray(json: JSONArray?): List<SpotifyArtist>? {
        return json?.let { (0 until it.length()).mapNotNull { index -> parseArtist(it.optJSONObject(index)) } }
    }

    private fun parseArtist(json: JSONObject?): SpotifyArtist? {
        return json?.let {
            val genresArray = it.optJSONArray("genres")
            val genres = if (genresArray != null) {
                (0 until genresArray.length()).mapNotNull { index -> genresArray.optString(index).takeIf { it.isNotBlank() } }
            } else {
                emptyList<String>()
            }
            SpotifyArtist(
                id = it.optString("id"),
                uri = it.optString("uri"),
                name = it.optString("name"),
                popularity = it.optInt("popularity"),
                genres = genres,
                externalUrls = parseExternalUrls(it.optJSONObject("external_urls")),
                images = parseImages(it.optJSONArray("images")),
                followers = it.optJSONObject("followers")?.optLong("total", 0) ?: 0,
                type = it.optString("type"),
                href = it.optString("href")
            )
        }
    }

    private fun parseAlbumArray(json: JSONArray?): List<SpotifyAlbum>? {
        return json?.let { (0 until it.length()).mapNotNull { index -> parseAlbum(it.optJSONObject(index)) } }
    }

    private fun parseAlbum(json: JSONObject?): SpotifyAlbum? {
        return json?.let {
            SpotifyAlbum(
                id = it.optString("id"),
                uri = it.optString("uri"),
                name = it.optString("name"),
                albumType = it.optString("album_type"),
                totalTracks = it.optInt("total_tracks"),
                artists = parseArtistArray(it.optJSONArray("artists")) ?: emptyList(),
                releaseDate = it.optString("release_date"),
                releaseDatePrecision = it.optString("release_date_precision"),
                externalUrls = parseExternalUrls(it.optJSONObject("external_urls")),
                images = parseImages(it.optJSONArray("images")),
                label = it.optString("label"),
                popularity = it.optInt("popularity"),
                type = it.optString("type"),
                href = it.optString("href")
            )
        }
    }

    private fun parsePlaylistArray(json: JSONArray?): List<SpotifyPlaylist>? {
        return json?.let { (0 until it.length()).mapNotNull { index -> parsePlaylist(it.optJSONObject(index)) } }
    }

    private fun parsePlaylist(json: JSONObject?): SpotifyPlaylist? {
        return json?.let {
            SpotifyPlaylist(
                id = it.optString("id"),
                uri = it.optString("uri"),
                name = it.optString("name"),
                description = it.optString("description"),
                owner = parseUser(it.optJSONObject("owner")),
                public = it.optBoolean("public"),
                collaborative = it.optBoolean("collaborative"),
                trackCount = it.optJSONObject("tracks")?.optInt("total", 0) ?: 0,
                images = parseImages(it.optJSONArray("images")),
                externalUrls = parseExternalUrls(it.optJSONObject("external_urls")),
                snapshotId = it.optString("snapshot_id"),
                type = it.optString("type"),
                href = it.optString("href")
            )
        }
    }

    private fun parseUser(json: JSONObject?): SpotifyUser? {
        return json?.let {
            SpotifyUser(
                id = it.optString("id"),
                uri = it.optString("uri"),
                displayName = it.optString("display_name"),
                externalUrls = parseExternalUrls(it.optJSONObject("external_urls")),
                images = parseImages(it.optJSONArray("images")),
                type = it.optString("type"),
                href = it.optString("href")
            )
        }
    }

    private fun parseExternalUrls(json: JSONObject?): Map<String, String> {
        return json?.keys()?.asSequence()
            ?.mapNotNull { key -> json.optString(key).takeIf { it.isNotBlank() }?.let { key to it } }
            ?.toMap() ?: emptyMap()
    }

    private fun parseImages(json: JSONArray?): List<SpotifyImage> {
        return json?.let { (0 until it.length()).mapNotNull { index -> parseImage(it.optJSONObject(index)) } } ?: emptyList()
    }

    private fun parseImage(json: JSONObject?): SpotifyImage? {
        return json?.let {
            SpotifyImage(
                url = it.optString("url"),
                width = it.optInt("width"),
                height = it.optInt("height")
            )
        }
    }

    private fun parseCategoryPage(json: JSONObject): SpotifyPage<SpotifyCategory> {
        return SpotifyPage(
            items = parseCategoryArray(json.optJSONArray("items")) ?: emptyList(),
            total = json.optInt("total"),
            limit = json.optInt("limit"),
            offset = json.optInt("offset"),
            next = json.optString("next").takeIf { it.isNotBlank() },
            previous = json.optString("previous").takeIf { it.isNotBlank() }
        )
    }

    private fun parseCategoryArray(json: JSONArray?): List<SpotifyCategory>? {
        return json?.let { (0 until it.length()).mapNotNull { index -> parseCategory(it.optJSONObject(index)) } }
    }

    private fun parseCategory(json: JSONObject?): SpotifyCategory? {
        return json?.let {
            SpotifyCategory(
                id = it.optString("id"),
                name = it.optString("name"),
                icons = parseImages(it.optJSONArray("icons")),
                href = it.optString("href")
            )
        }
    }
}

// Data models
data class SpotifySearchResult(
    val tracks: SpotifyPage<SpotifyTrack>? = null,
    val artists: SpotifyPage<SpotifyArtist>? = null,
    val albums: SpotifyPage<SpotifyAlbum>? = null,
    val playlists: SpotifyPage<SpotifyPlaylist>? = null
)

data class SpotifyPage<T>(
    val items: List<T>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val next: String?,
    val previous: String?
)

data class SpotifyTrack(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<SpotifyArtist>,
    val album: SpotifyAlbum?,
    val durationMs: Long,
    val explicit: Boolean,
    val popularity: Int,
    val previewUrl: String?,
    val externalUrls: Map<String, String>,
    val images: List<SpotifyImage>,
    val isPlayable: Boolean,
    val trackNumber: Int,
    val discNumber: Int,
    val type: String,
    val href: String
)

data class SpotifyArtist(
    val id: String,
    val uri: String,
    val name: String,
    val popularity: Int,
    val genres: List<String>,
    val externalUrls: Map<String, String>,
    val images: List<SpotifyImage>,
    val followers: Long,
    val type: String,
    val href: String
)

data class SpotifyAlbum(
    val id: String,
    val uri: String,
    val name: String,
    val albumType: String,
    val totalTracks: Int,
    val artists: List<SpotifyArtist>,
    val releaseDate: String,
    val releaseDatePrecision: String,
    val externalUrls: Map<String, String>,
    val images: List<SpotifyImage>,
    val label: String,
    val popularity: Int,
    val type: String,
    val href: String
)

data class SpotifyPlaylist(
    val id: String,
    val uri: String,
    val name: String,
    val description: String,
    val owner: SpotifyUser?,
    val public: Boolean,
    val collaborative: Boolean,
    val trackCount: Int,
    val images: List<SpotifyImage>,
    val externalUrls: Map<String, String>,
    val snapshotId: String,
    val type: String,
    val href: String
)

data class SpotifyUser(
    val id: String,
    val uri: String,
    val displayName: String,
    val externalUrls: Map<String, String>,
    val images: List<SpotifyImage>,
    val type: String,
    val href: String
)

data class SpotifyCategory(
    val id: String,
    val name: String,
    val icons: List<SpotifyImage>,
    val href: String
)

data class SpotifyShow(
    val id: String,
    val uri: String,
    val name: String,
    val description: String,
    val publisher: String,
    val externalUrls: Map<String, String>,
    val images: List<SpotifyImage>,
    val type: String,
    val href: String
)