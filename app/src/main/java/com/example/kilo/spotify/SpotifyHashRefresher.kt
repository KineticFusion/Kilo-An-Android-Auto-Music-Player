package com.example.kilo.spotify

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.serializer

class SpotifyHashRefresher(
    private val graphqlClient: SpotifyGraphqlClient,
    private val hashProvider: SpotifyHashProvider,
    private val hashDiscovery: SpotifyHashDiscovery
) {

    companion object {
        private const val TAG = "SpotifyHashRefresher"
        private const val MAX_REFRESH_ATTEMPTS = 3
    }

    private val refreshingOperations = mutableSetOf<String>()
    private val failedOperations = mutableMapOf<String, Int>()

    suspend fun invalidateAndRefresh(operationName: String) = withContext(Dispatchers.IO) {
        if (refreshingOperations.contains(operationName)) {
            // Already refreshing, wait a bit and retry
            return@withContext
        }

        val attempts = failedOperations.getOrDefault(operationName, 0)
        if (attempts >= MAX_REFRESH_ATTEMPTS) {
            Log.w(TAG, "Max refresh attempts reached for $operationName")
            return@withContext
        }

        refreshingOperations.add(operationName)
        try {
            val newHash = discoverAndValidateHash(operationName)
            newHash?.let { hash ->
                hashProvider.setHash(operationName, hash)
                failedOperations.remove(operationName)
                Log.d(TAG, "Successfully refreshed hash for $operationName")
            } ?: run {
                failedOperations[operationName] = attempts + 1
                Log.w(TAG, "Failed to refresh hash for $operationName (attempt ${attempts + 1})")
            }
        } finally {
            refreshingOperations.remove(operationName)
        }
    }

    private suspend fun discoverAndValidateHash(operationName: String): String? {
        val context = graphqlClient.context
        val discoveredHash = hashDiscovery.discoverHashForOperation(context, operationName)

        if (discoveredHash != null) {
            // Validate the hash by making a test request
            if (validateHash(operationName, discoveredHash)) {
                return discoveredHash
            }
        }

        return null
    }

    private suspend fun validateHash(operationName: String, hash: String): Boolean {
        // Try a minimal request to validate the hash works
        return try {
            val testVariables = getTestVariablesForOperation(operationName)
            val result = graphqlClient.executeGraphQL<String>(
                operationName = operationName,
                variables = testVariables,
                responseType = serializer<String>()
            )
            result is SpotifyResult.Success
        } catch (e: Exception) {
            false
        }
    }

    private fun getTestVariablesForOperation(operationName: String): Map<String, Any> {
        return when (operationName) {
            "getAlbum" -> mapOf("uri" to "spotify:album:4aawyAB9vmqN3uQ7FjRGTy", "locale" to "")
            "getArtist" -> mapOf("uri" to "spotify:artist:0TnOYISbd1XYRBk9myaseg", "locale" to "")
            "getPlaylist" -> mapOf("uri" to "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", "locale" to "")
            "getPlaylistTracks" -> mapOf("uri" to "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", "limit" to 1, "offset" to 0)
            "getLibraryTracks" -> mapOf("limit" to 1, "offset" to 0)
            "getLibraryAlbums" -> mapOf("limit" to 1, "offset" to 0)
            "getLibraryArtists" -> mapOf("limit" to 1, "offset" to 0)
            "getLibraryPlaylists" -> mapOf("limit" to 1, "offset" to 0)
            "search" -> mapOf("query" to "test", "types" to listOf("track"), "limit" to 1, "offset" to 0)
            "getHomeFeed" -> mapOf("limit" to 1)
            "getRecommendations" -> mapOf("limit" to 1, "seedTracks" to listOf("4uLU6hMCjMI75M1A2tKUQC"))
            else -> emptyMap()
        }
    }

    suspend fun refreshAllHashes(context: Context) = withContext(Dispatchers.IO) {
        val discoveredHashes = hashDiscovery.discoverHashesFromWebPlayer(context)
        val validatedHashes = mutableMapOf<String, String>()

        for ((operationName, hash) in discoveredHashes) {
            if (validateHash(operationName, hash)) {
                validatedHashes[operationName] = hash
            }
        }

        if (validatedHashes.isNotEmpty()) {
            hashProvider.setHashes(validatedHashes)
            hashProvider.saveToPrefs(context)
            Log.d(TAG, "Refreshed ${validatedHashes.size} hashes")
        }
    }

    fun getRefreshStatus(operationName: String): RefreshStatus {
        return if (refreshingOperations.contains(operationName)) {
            RefreshStatus.REFRESHING
        } else if (failedOperations.containsKey(operationName)) {
            RefreshStatus.FAILED(failedOperations[operationName]!!)
        } else {
            RefreshStatus.IDLE
        }
    }

    sealed class RefreshStatus {
        object IDLE : RefreshStatus()
        object REFRESHING : RefreshStatus()
        data class FAILED(val attempts: Int) : RefreshStatus()
    }
}