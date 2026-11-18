package com.example.kilo.spotify

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class SpotifyHashProvider {

    companion object {
        private const val PREFS_NAME = "spotify_graphql_hashes"
        private const val KEY_HASHES = "operation_hashes"
        private const val KEY_VERSION = "hash_version"
    }

    @Volatile
    private var memoryCache: Map<String, String> = emptyMap()

    @Volatile
    private var currentVersion = 0

    private val defaultHashes = mapOf(
        "getAlbum" to "a1b2c3d4e5f6...",
        "getArtist" to "b2c3d4e5f6a7...",
        "getArtistDiscography" to "c3d4e5f6a7b8...",
        "getArtistRelatedArtists" to "d4e5f6a7b8c9...",
        "getHomeFeed" to "e5f6a7b8c9d0...",
        "getRecommendations" to "f6a7b8c9d0e1...",
        "getPlaylist" to "a7b8c9d0e1f2...",
        "getPlaylistTracks" to "b8c9d0e1f2a3...",
        "createPlaylist" to "c9d0e1f2a3b4...",
        "addTracksToPlaylist" to "d0e1f2a3b4c5...",
        "removeTracksFromPlaylist" to "e1f2a3b4c5d6...",
        "reorderPlaylistTracks" to "f2a3b4c5d6e7...",
        "getLibraryTracks" to "a3b4c5d6e7f8...",
        "getLibraryAlbums" to "b4c5d6e7f8a9...",
        "getLibraryArtists" to "c5d6e7f8a9b0...",
        "getLibraryPlaylists" to "d6e7f8a9b0c1...",
        "saveTrack" to "e7f8a9b0c1d2...",
        "removeSavedTrack" to "f8a9b0c1d2e3...",
        "saveAlbum" to "a9b0c1d2e3f4...",
        "removeSavedAlbum" to "b0c1d2e3f4a5...",
        "followArtist" to "c1d2e3f4a5b6...",
        "unfollowArtist" to "d2e3f4a5b6c7...",
        "search" to "e3f4a5b6c7d8..."
    )

    fun getHash(operationName: String): String? {
        return memoryCache[operationName] ?: defaultHashes[operationName]
    }

    fun setHash(operationName: String, hash: String) {
        memoryCache = memoryCache + (operationName to hash)
    }

    fun setHashes(hashes: Map<String, String>) {
        memoryCache = hashes
        currentVersion++
    }

    fun getAllHashes(): Map<String, String> {
        return memoryCache.ifEmpty { defaultHashes }
    }

    fun getVersion(): Int = currentVersion

    suspend fun loadFromPrefs(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HASHES, null)
        val version = prefs.getInt(KEY_VERSION, 0)

        if (json != null && json.isNotBlank()) {
            try {
                val parsed = kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(json)
                memoryCache = parsed
                currentVersion = version
            } catch (e: Exception) {
                // Fall back to defaults
            }
        }
    }

    suspend fun saveToPrefs(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonObj = buildJsonObject {
            memoryCache.forEach { (key, value) ->
                put(key, JsonPrimitive(value))
            }
        }
        val json: String = jsonObj.toString()
        prefs.edit()
            .putString(KEY_HASHES, json)
            .putInt(KEY_VERSION, currentVersion)
            .apply()
    }

    fun clear() {
        memoryCache = emptyMap()
        currentVersion = 0
    }
}