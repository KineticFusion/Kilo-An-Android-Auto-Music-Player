package com.example.kilo.spotify

import android.content.Context
import android.content.SharedPreferences
import com.example.kilo.spotify.auth.SpotifyTotpGenerator
import com.example.kilo.spotify.SpotifyError
import com.example.kilo.spotify.SpotifyResult
import com.example.kilo.spotify.SpotifySession
import com.example.kilo.streaming.HybridSourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SpotifyAuth(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "spotify_auth_session"
        private const val KEY_SESSION = "session_data"
        private const val KEY_SP_DC = "sp_dc_cookie"
        private const val KEY_CLIENT_TOKEN = "client_token"
        private const val KEY_CLIENT_TOKEN_EXPIRY = "client_token_expiry"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cachedSession: SpotifySession? = null

    @Volatile
    private var cachedClientToken: String? = null

    @Volatile
    private var clientTokenExpiry: Long = 0

    init {
        loadSession()
    }

    suspend fun getValidSession(): SpotifySession? {
        val session = cachedSession ?: loadSession()
        if (session != null && !session.isExpired) {
            return session
        }
        // Try to refresh
        return refreshSession()
    }

    suspend fun getAccessToken(): String? {
        return getValidSession()?.accessToken
    }

    suspend fun getClientToken(): String? {
        if (cachedClientToken != null && System.currentTimeMillis() < clientTokenExpiry) {
            return cachedClientToken
        }
        return fetchClientToken()
    }

    fun saveWebSession(spDcCookie: String) {
        prefs.edit().putString(KEY_SP_DC, spDcCookie).apply()
        cachedSession = null // Force re-auth
    }

    fun getSpDcCookie(): String? {
        return prefs.getString(KEY_SP_DC, null)?.takeIf { it.isNotBlank() }
    }

    fun isLoggedIn(): Boolean {
        return getSpDcCookie() != null
    }

    fun logout() {
        prefs.edit().clear().apply()
        cachedSession = null
        cachedClientToken = null
        clientTokenExpiry = 0
        HybridSourceRegistry.clearSpotifyWebSession(context)
    }

    private fun loadSession(): SpotifySession? {
        val json = prefs.getString(KEY_SESSION, null)
        if (json != null && json.isNotBlank()) {
            try {
                cachedSession = kotlinx.serialization.json.Json.decodeFromString<SpotifySession>(json)
                return cachedSession
            } catch (e: Exception) {
                // Ignore, will re-auth
            }
        }
        return null
    }

    private fun saveSession(session: SpotifySession) {
        cachedSession = session
        val json = kotlinx.serialization.json.Json.encodeToString(SpotifySession.serializer(), session)
        prefs.edit().putString(KEY_SESSION, json).apply()
    }

    private suspend fun refreshSession(): SpotifySession? {
        val spDc = getSpDcCookie() ?: return null
        return try {
            val session = exchangeSpDcForToken(spDc)
            session?.let { saveSession(it) }
            session
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun exchangeSpDcForToken(spDc: String): SpotifySession? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://open.spotify.com/get_access_token?reason=transport&productType=web-player")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Cookie", "sp_dc=$spDc")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            connection.connectTimeout = 10000
            connection.readTimeout = 15000

            if (connection.responseCode !in 200..299) {
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            return@withContext parseAccessTokenResponse(response)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAccessTokenResponse(jsonString: String): SpotifySession? {
        try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val obj = json.decodeFromString<com.example.kilo.spotify.SpotifyAccessTokenResponse>(jsonString)
            return SpotifySession(
                accessToken = obj.accessToken,
                tokenType = "Bearer",
                expiresIn = (obj.accessTokenExpirationTimestampMs - System.currentTimeMillis()).toInt(),
                refreshToken = null,
                scope = "",
                expiresAt = obj.accessTokenExpirationTimestampMs
            )
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun fetchClientToken(): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://clienttoken.spotify.com/v1/token")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 10000

            if (connection.responseCode !in 200..299) {
                return@withContext null
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val token = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<com.example.kilo.spotify.ClientTokenResponse>(response)
                .clientToken

            cachedClientToken = token
            clientTokenExpiry = System.currentTimeMillis() + 3600000 // 1 hour
            return@withContext token
        } catch (e: Exception) {
            return@withContext null
        }
    }

    fun getTotpCode(): String {
        val spDc = getSpDcCookie() ?: return ""
        // Derive TOTP secret from sp_dc (simplified)
        val secret = SpotifyTotpGenerator.deriveSecretFromClientId(spDc.substring(0, minOf(32, spDc.length)))
        return SpotifyTotpGenerator.generateTotp(secret)
    }
}

// DTOs for auth responses
