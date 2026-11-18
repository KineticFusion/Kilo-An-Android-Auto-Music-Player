package com.example.kilo.spotify

import android.content.Context
import com.example.kilo.spotify.SpotifyError
import com.example.kilo.spotify.SpotifyResult
import com.example.kilo.spotify.SpotifySession
import com.example.kilo.spotify.parser.SpotifyJsonParser
import com.example.kilo.streaming.HybridSourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SpotifyGraphqlClient(
    val context: Context,
    private val sessionManager: SpotifyAuth
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val hashProvider = SpotifyHashProvider()
    private val hashDiscovery = SpotifyHashDiscovery()
    private val hashRefresher = SpotifyHashRefresher(this, hashProvider, hashDiscovery)

    suspend fun <T> executeGraphQL(
        operationName: String,
        variables: Map<String, Any> = emptyMap(),
        responseType: kotlinx.serialization.KSerializer<T>
    ): SpotifyResult<T> = withContext(Dispatchers.IO) {
        val session = sessionManager.getValidSession()
            ?: return@withContext SpotifyResult.Failure(SpotifyError(message = "No valid session", status = 401))

        var attempt = 0
        while (attempt < 2) {
            val hash = hashProvider.getHash(operationName)
                ?: return@withContext SpotifyResult.Failure(SpotifyError(message = "No hash for operation: $operationName", status = 500))

            val result = executeRequest(operationName, hash, variables, session.accessToken, responseType)

            if (result is SpotifyResult.Success) {
                return@withContext result
            } else if (result is SpotifyResult.Failure && result.error.status == 400) {
                // Check if it's a PersistedQueryNotFound error
                if (isPersistedQueryNotFound(result.error.message)) {
                    attempt++
                    hashRefresher.invalidateAndRefresh(operationName)
                    continue
                }
            }

            return@withContext result
        }

        SpotifyResult.Failure(SpotifyError(message = "Max retries exceeded for $operationName", status = 500))
    }

    private suspend fun <T> executeRequest(
        operationName: String,
        hash: String,
        variables: Map<String, Any>,
        accessToken: String,
        responseType: kotlinx.serialization.KSerializer<T>
    ): SpotifyResult<T> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api-partner.spotify.com/pathfinder/v1/query")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Spotify-Client-Token", getClientToken())
            connection.connectTimeout = 10000
            connection.readTimeout = 15000

            val requestBody = buildRequestBody(operationName, variables, hash)

            connection.outputStream.use { it.write(requestBody.toByteArray()) }

            val responseCode = connection.responseCode
            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }

            if (responseCode !in 200..299) {
                val error = SpotifyJsonParser.parseErrorResponse(responseBody)
                    ?: SpotifyError(status = responseCode, message = "HTTP $responseCode")
                return@withContext SpotifyResult.Failure(error)
            }

            val data = json.decodeFromString(responseType, responseBody)
            return@withContext SpotifyResult.Success(data)

        } catch (e: Exception) {
            return@withContext SpotifyResult.Failure(SpotifyError(message = e.message ?: "Network error", status = 0))
        }
    }

    private fun isPersistedQueryNotFound(message: String?): Boolean {
        return message?.contains("PersistedQueryNotFound", ignoreCase = true) == true
    }

    private suspend fun getClientToken(): String {
        // This would typically come from the session or a separate client token endpoint
        return sessionManager.getClientToken() ?: ""
    }

    suspend fun refreshAllHashes(context: Context) = hashRefresher.refreshAllHashes(context)

    private suspend fun buildRequestBody(
        operationName: String,
        variables: Map<String, Any>,
        hash: String
    ): String = withContext(Dispatchers.Default) {
        // Use JSONObject for dynamic serialization since variables can be Any
        val jsonObject = org.json.JSONObject()
        jsonObject.put("operationName", operationName)
        jsonObject.put("variables", org.json.JSONObject(variables))
        val extensions = org.json.JSONObject()
        val persistedQuery = org.json.JSONObject()
        persistedQuery.put("version", 1)
        persistedQuery.put("sha256Hash", hash)
        extensions.put("persistedQuery", persistedQuery)
        jsonObject.put("extensions", extensions)
        jsonObject.toString()
    }
}