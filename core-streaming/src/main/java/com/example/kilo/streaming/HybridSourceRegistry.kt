package com.example.kilo.streaming

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.abs

/**
 * Central registry managing built-in YouTube Music, Spotify Web-Session Client,
 * and user-installed BitChord-style FLAC/catalog addons.
 */
object HybridSourceRegistry {
    private const val TAG = "KiloSourceRegistry"
    private const val PREFS = "vybe_app_settings"
    private const val KEY_SOURCES_JSON = "hybrid_configured_sources_v2"
    private const val KEY_TOKEN_PREFIX = "hybrid_source_token_"
    private const val KEY_SPOTIFY_COOKIE = "hybrid_spotify_sp_dc_cookie"
    private const val KEY_SPOTIFY_WEB_TOKEN = "hybrid_spotify_web_access_token"
    private const val KEY_SPOTIFY_TOKEN_EXPIRY = "hybrid_spotify_web_token_expiry"
    private const val KEYSTORE_ALIAS = "kilo_hybrid_source_master_key"

    const val SOURCE_ID_YOUTUBE = "yt"
    const val SOURCE_ID_SPOTIFY = "spotify"

    private const val KEY_SOURCE_MODE = "hybrid_source_mode"
    const val SOURCE_MODE_BOTH = "both"
    const val SOURCE_MODE_SPOTIFY_ONLY = "spotify_only"
    const val SOURCE_MODE_YOUTUBE_ONLY = "youtube_only"

    private val providers = ConcurrentHashMap<String, AudioSourceProvider>()
    private val _sources = MutableStateFlow<List<ConfiguredSource>>(emptyList())
    val sources: StateFlow<List<ConfiguredSource>> = _sources.asStateFlow()

    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            val loaded = loadOrMigrateSources(appCtx)
            _sources.value = loaded.sortedBy { it.priority }
            rebuildProviders(appCtx, _sources.value)
            initialized = true
        }
    }

    fun registerProvider(provider: AudioSourceProvider) {
        providers[provider.config.id] = provider
    }

    fun getProvider(sourceId: String): AudioSourceProvider? = providers[sourceId]

    fun isYoutubeEnabled(context: Context): Boolean {
        ensureInitialized(context)
        return _sources.value.firstOrNull { it.id == SOURCE_ID_YOUTUBE }?.enabled ?: true
    }

    fun isSpotifyEnabled(context: Context): Boolean {
        ensureInitialized(context)
        return _sources.value.firstOrNull { it.id == SOURCE_ID_SPOTIFY }?.enabled ?: false
    }

    fun getOrderedActiveProviders(context: Context, losslessOnly: Boolean = false): List<AudioSourceProvider> {
        ensureInitialized(context)
        val mode = getSourceMode(context)
        return _sources.value
            .filter { src ->
                val modeMatch = when (mode) {
                    SOURCE_MODE_SPOTIFY_ONLY -> src.id == SOURCE_ID_SPOTIFY
                    SOURCE_MODE_YOUTUBE_ONLY -> src.id == SOURCE_ID_YOUTUBE
                    else -> true // BOTH
                }
                src.enabled && modeMatch && (!losslessOnly || src.supportsLossless)
            }
            .sortedBy { it.priority }
            .mapNotNull { providers[it.id] }
    }

    fun getSourceMode(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE_MODE, SOURCE_MODE_BOTH) ?: SOURCE_MODE_BOTH
    }

    fun setSourceMode(context: Context, mode: String) {
        val validMode = when (mode) {
            SOURCE_MODE_SPOTIFY_ONLY, SOURCE_MODE_YOUTUBE_ONLY, SOURCE_MODE_BOTH -> mode
            else -> SOURCE_MODE_BOTH
        }
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SOURCE_MODE, validMode).apply()
        // Rebuild providers to reflect the mode change immediately
        ensureInitialized(appCtx)
        rebuildProviders(appCtx, _sources.value)
    }

    /** Saves the open.spotify.com session cookie & web access token (encrypted in Keystore) and enables Spotify. */
    fun saveSpotifyWebSession(
        context: Context,
        cookie: String,
        accessToken: String = "",
        expiresAtMs: Long = 0L
    ) {
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)
        val editor = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (cookie.isNotBlank()) editor.putString(KEY_SPOTIFY_COOKIE, encrypt(cookie.trim()))
        if (accessToken.isNotBlank()) {
            editor.putString(KEY_SPOTIFY_WEB_TOKEN, encrypt(accessToken.trim()))
            editor.putLong(KEY_SPOTIFY_TOKEN_EXPIRY, expiresAtMs)
        }
        editor.apply()

        val updated = _sources.value.map {
            if (it.id == SOURCE_ID_SPOTIFY) {
                it.copy(
                    enabled = true,
                    statusMessage = "Connected • Spotify Web Session"
                )
            } else it
        }
        persistSources(appCtx, updated)
    }

    fun getSpotifyWebSession(context: Context): Triple<String, String, Long> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cookie = prefs.getString(KEY_SPOTIFY_COOKIE, null)?.let(::decrypt).orEmpty()
        val token = prefs.getString(KEY_SPOTIFY_WEB_TOKEN, null)?.let(::decrypt).orEmpty()
        val expiry = prefs.getLong(KEY_SPOTIFY_TOKEN_EXPIRY, 0L)
        return Triple(cookie, token, expiry)
    }

    /**
     * Gets a valid Spotify web access token, refreshing it if expired.
     * Uses the open.spotify.com/get_access_token endpoint with the stored cookie.
     */
    suspend fun ensureValidSpotifyWebToken(context: Context): String? = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cookie = prefs.getString(KEY_SPOTIFY_COOKIE, null)?.let(::decrypt).orEmpty()
        val savedToken = prefs.getString(KEY_SPOTIFY_WEB_TOKEN, null)?.let(::decrypt).orEmpty()
        val savedExpiry = prefs.getLong(KEY_SPOTIFY_TOKEN_EXPIRY, 0L)

        // Return cached token if still valid (with 15s buffer)
        if (savedToken.isNotBlank() && System.currentTimeMillis() < savedExpiry - 15_000L) {
            return@withContext savedToken
        }

        // Refresh token using cookie
        if (cookie.isBlank()) {
            return@withContext null
        }

        runCatching {
            val conn = (URL("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                setRequestProperty("Cookie", cookie)
            }
            if (conn.responseCode !in 200..299) {
                Log.w("KiloHybridRegistry", "Failed to refresh Spotify token: HTTP ${conn.responseCode}")
                return@runCatching null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val token = json.optString("accessToken")
            val expiry = json.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis() + 1800_000L)
            if (token.isNotBlank()) {
                saveSpotifyWebSession(appCtx, cookie, token, expiry)
                token
            } else null
        }.getOrNull()
    }

    fun clearSpotifyWebSession(context: Context) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_SPOTIFY_COOKIE)
            .remove(KEY_SPOTIFY_WEB_TOKEN)
            .remove(KEY_SPOTIFY_TOKEN_EXPIRY)
            .apply()

        // Disable Spotify source
        val updated = _sources.value.map {
            if (it.id == SOURCE_ID_SPOTIFY) {
                it.copy(
                    enabled = false,
                    statusMessage = "Not connected"
                )
            } else it
        }
        persistSources(appCtx, updated)
    }

    /**
     * Installs or updates a BitChord-style addon via its HTTPS manifest.json URL.
     */
    suspend fun installOrUpdateAddon(
        context: Context,
        manifestOrRootUrl: String,
        token: String = "",
        customName: String = "",
        priority: Int = 10
    ): Result<ConfiguredSource> = withContext(Dispatchers.IO) {
        runCatching {
            val appCtx = context.applicationContext
            ensureInitialized(appCtx)

            val trimmedUrl = manifestOrRootUrl.trim()
            val uri = URL(trimmedUrl)
            require(uri.protocol == "https") { "Addon URL must use secure HTTPS." }
            require(uri.host.isNotBlank()) { "Enter a valid addon manifest URL." }

            val normalizedRoot = trimmedUrl.removeSuffix("/manifest.json").trimEnd('/')
            val manifestUrl = if (trimmedUrl.endsWith("manifest.json")) trimmedUrl else "$normalizedRoot/manifest.json"

            val manifest = ManifestAddonSourceProvider.fetchJson(manifestUrl, token.trim(), timeoutMs = 4500)
            val rawManifestId = manifest.optString("id").trim()
            require(rawManifestId.isNotBlank()) { "Addon manifest is missing a valid 'id'." }

            val safeSourceId = rawManifestId.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
            require(safeSourceId != SOURCE_ID_YOUTUBE && safeSourceId != SOURCE_ID_SPOTIFY) {
                "Addon ID conflicts with built-in YouTube or Spotify source."
            }

            val resources = manifest.optJSONArray("resources")
            val hasSearch = resources != null && (0 until resources.length()).any { resources.optString(it) == "search" }
            val hasStream = resources != null && (0 until resources.length()).any { resources.optString(it) == "stream" }
            require(hasSearch && hasStream) { "Addon manifest must support both 'search' and 'stream' resources." }

            val effectiveToken = token.trim().ifBlank { getToken(appCtx, safeSourceId).orEmpty() }
            if (effectiveToken.isNotBlank()) {
                saveToken(appCtx, safeSourceId, effectiveToken)
            }

            val resolvedName = customName.trim().ifBlank {
                manifest.optString("name").ifBlank { rawManifestId }
            }
            val version = manifest.optString("version", "1.0.0")
            val description = manifest.optString("description", "External catalog & lossless stream addon")
            val supportsLossless = manifest.optBoolean("lossless", true)

            val existing = _sources.value.find { it.id == safeSourceId }
            val addonConfig = ConfiguredSource(
                id = safeSourceId,
                name = resolvedName,
                type = SourceType.ADDON_MANIFEST,
                baseUrl = normalizedRoot,
                priority = existing?.priority ?: priority,
                enabled = true,
                supportsLossless = supportsLossless,
                version = version,
                description = description,
                statusMessage = "Connected • v$version"
            )

            val updatedList = _sources.value.toMutableList()
            val idx = updatedList.indexOfFirst { it.id == safeSourceId }
            if (idx >= 0) updatedList[idx] = addonConfig else updatedList.add(addonConfig)

            persistSources(appCtx, updatedList)
            addonConfig
        }
    }

    fun setSourceEnabled(context: Context, sourceId: String, enabled: Boolean) {
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)
        val updated = _sources.value.map {
            if (it.id == sourceId) {
                it.copy(
                    enabled = enabled,
                    statusMessage = if (enabled) "Active" else "Disabled"
                )
            } else it
        }
        val hasCoreEnabled = updated.any { (it.id == SOURCE_ID_YOUTUBE || it.id == SOURCE_ID_SPOTIFY) && it.enabled }
        val safeList = if (!hasCoreEnabled) {
            updated.map { if (it.id == SOURCE_ID_YOUTUBE) it.copy(enabled = true, statusMessage = "Active (Fallback)") else it }
        } else updated

        persistSources(appCtx, safeList)
    }

    fun moveSourcePriority(context: Context, sourceId: String, newPriority: Int) {
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)
        val updated = _sources.value.map {
            if (it.id == sourceId) it.copy(priority = newPriority) else it
        }
        persistSources(appCtx, updated)
    }

    fun uninstallAddon(context: Context, sourceId: String) {
        if (sourceId == SOURCE_ID_YOUTUBE || sourceId == SOURCE_ID_SPOTIFY) return
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TOKEN_PREFIX + sourceId)
            .apply()
        providers.remove(sourceId)
        val updated = _sources.value.filterNot { it.id == sourceId }
        persistSources(appCtx, updated)
    }

    internal fun getToken(context: Context, sourceId: String): String? {
        val encrypted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN_PREFIX + sourceId, null) ?: return null
        return decrypt(encrypted)
    }

    private fun saveToken(context: Context, sourceId: String, rawToken: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN_PREFIX + sourceId, encrypt(rawToken))
            .apply()
    }

    private fun persistSources(context: Context, list: List<ConfiguredSource>) {
        val sorted = list.sortedBy { it.priority }
        val array = JSONArray()
        sorted.forEach { src ->
            array.put(JSONObject().apply {
                put("id", src.id)
                put("name", src.name)
                put("type", src.type.name)
                put("baseUrl", src.baseUrl)
                put("priority", src.priority)
                put("enabled", src.enabled)
                put("supportsLossless", src.supportsLossless)
                put("version", src.version)
                put("description", src.description)
                put("statusMessage", src.statusMessage)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCES_JSON, array.toString())
            .apply()

        _sources.value = sorted
        rebuildProviders(context, sorted)
    }

    private fun rebuildProviders(context: Context, sourcesList: List<ConfiguredSource>) {
        sourcesList.forEach { src ->
            when (src.type) {
                SourceType.YOUTUBE -> providers[src.id] = YouTubeMusicSourceProvider(context, src)
                SourceType.ADDON_MANIFEST -> providers[src.id] = ManifestAddonSourceProvider(context, src)
                SourceType.SPOTIFY -> providers[src.id] = SpotifyCatalogBridgeProvider(context, src)
                else -> Unit
            }
        }
    }

    private fun loadOrMigrateSources(context: Context): List<ConfiguredSource> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_SOURCES_JSON, null)
        if (!rawJson.isNullOrBlank()) {
            return runCatching {
                val arr = JSONArray(rawJson)
                val parsed = (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    ConfiguredSource(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        type = SourceType.valueOf(obj.optString("type", SourceType.ADDON_MANIFEST.name)),
                        baseUrl = obj.optString("baseUrl", ""),
                        priority = obj.optInt("priority", 50),
                        enabled = obj.optBoolean("enabled", true),
                        supportsLossless = obj.optBoolean("supportsLossless", false),
                        version = obj.optString("version", "1.0.0"),
                        description = obj.optString("description", ""),
                        statusMessage = obj.optString("statusMessage", "Ready")
                    )
                }.toMutableList()
                defaultSources().forEach { def ->
                    if (parsed.none { it.id == def.id }) parsed.add(def)
                }
                parsed
            }.getOrElse { defaultSources() }
        }

        val initial = defaultSources().toMutableList()
        val legacyUrl = prefs.getString("lossless_source_url", "").orEmpty().trim()
        if (legacyUrl.isNotBlank()) {
            val legacyName = prefs.getString("lossless_source_provider", "BitChord Addon").orEmpty().ifBlank { "BitChord Addon" }
            val legacyEnabled = prefs.getBoolean("lossless_source_enabled", false)
            initial.add(
                ConfiguredSource(
                    id = "legacy_lossless_addon",
                    name = legacyName,
                    type = SourceType.ADDON_MANIFEST,
                    baseUrl = legacyUrl.trimEnd('/'),
                    priority = 10,
                    enabled = legacyEnabled,
                    supportsLossless = true,
                    description = "Migrated lossless catalog addon",
                    statusMessage = if (legacyEnabled) "Migrated • Active" else "Disabled"
                )
            )
        }
        persistSources(context, initial)
        return initial
    }

    private fun defaultSources(): List<ConfiguredSource> = listOf(
        ConfiguredSource(
            id = SOURCE_ID_YOUTUBE,
            name = "YouTube Music",
            type = SourceType.YOUTUBE,
            priority = 30,
            enabled = true,
            supportsLossless = false,
            version = "4.0.0",
            description = "InnerTube multi-client zero-hiccup audio & catalog",
            statusMessage = "Active"
        ),
        ConfiguredSource(
            id = SOURCE_ID_SPOTIFY,
            name = "Spotify",
            type = SourceType.SPOTIFY,
            priority = 40,
            enabled = true,
            supportsLossless = false,
            version = "4.0.0",
            description = "Spotify Web Player catalog bridged to active audio streams",
            statusMessage = "Active"
        )
    )

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? = runCatching {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
    }.getOrNull()
}

data class SpotifyPlaylistSummary(
    val id: String,
    val name: String,
    val coverUrl: String,
    val trackCount: Int
)

/**
 * Full Spotify Web-Session Client Bridge:
 * Fetches Spotify Search, Home/Featured shelves, Liked Songs, and User Playlists
 * using the open.spotify.com web token, and routes audio playback through Kilo's priority chain.
 */
class SpotifyCatalogBridgeProvider(
    context: Context,
    override val config: ConfiguredSource
) : AudioSourceProvider {

    private val appContext = context.applicationContext

    @Volatile
    private var cachedAccessToken: String = ""

    @Volatile
    private var tokenExpiresAtMs: Long = 0L

    override suspend fun search(query: String, limit: Int): List<HybridTrack> = withContext(Dispatchers.IO) {
        if (!config.enabled || query.isBlank()) return@withContext emptyList()
        val token = getAccessToken() ?: return@withContext emptyList()
        runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val json = spotifyGet(
                "https://api.spotify.com/v1/search?q=$encoded&type=track&limit=${limit.coerceIn(1, 25)}",
                token
            ) ?: return@runCatching emptyList()
            val items = json.optJSONObject("tracks")?.optJSONArray("items") ?: return@runCatching emptyList()
            parseSpotifyTrackArray(items)
        }.getOrElse {
            Log.w("KiloSpotifyBridge", "Spotify search failed: ${it.message}")
            emptyList()
        }
    }

    suspend fun fetchLikedTracks(limit: Int = 50): List<HybridTrack> = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: return@withContext emptyList()
        runCatching {
            val json = spotifyGet("https://api.spotify.com/v1/me/tracks?limit=${limit.coerceIn(1, 50)}", token)
                ?: return@runCatching emptyList()
            val items = json.optJSONArray("items") ?: return@runCatching emptyList()
            val unwrapped = JSONArray()
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.optJSONObject("track")?.let { unwrapped.put(it) }
            }
            parseSpotifyTrackArray(unwrapped)
        }.getOrDefault(emptyList())
    }

    suspend fun fetchHomeRecommendations(limit: Int = 25): List<HybridTrack> = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: return@withContext emptyList()
        runCatching {
            val topJson = spotifyGet("https://api.spotify.com/v1/me/top/tracks?limit=${limit.coerceIn(1, 30)}&time_range=short_term", token)
            val topItems = topJson?.optJSONArray("items")
            if (topItems != null && topItems.length() > 0) {
                return@runCatching parseSpotifyTrackArray(topItems)
            }
            search("year:2026", limit)
        }.getOrDefault(emptyList())
    }

    suspend fun fetchUserPlaylists(limit: Int = 30): List<SpotifyPlaylistSummary> = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: return@withContext emptyList()
        runCatching {
            val json = spotifyGet("https://api.spotify.com/v1/me/playlists?limit=${limit.coerceIn(1, 50)}", token)
                ?: return@runCatching emptyList()
            val items = json.optJSONArray("items") ?: return@runCatching emptyList()
            (0 until items.length()).mapNotNull { i ->
                val pl = items.optJSONObject(i) ?: return@mapNotNull null
                val id = pl.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = pl.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val cover = pl.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty()
                val total = pl.optJSONObject("tracks")?.optInt("total", 0) ?: 0
                SpotifyPlaylistSummary(id = id, name = name, coverUrl = cover, trackCount = total)
            }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchPlaylistTracks(playlistId: String, limit: Int = 100): List<HybridTrack> = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: return@withContext emptyList()
        runCatching {
            val cleanId = playlistId.removePrefix("spotify_pl_")
            val json = spotifyGet("https://api.spotify.com/v1/playlists/$cleanId/tracks?limit=${limit.coerceIn(1, 100)}", token)
                ?: return@runCatching emptyList()
            val items = json.optJSONArray("items") ?: return@runCatching emptyList()
            val unwrapped = JSONArray()
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.optJSONObject("track")?.let { unwrapped.put(it) }
            }
            parseSpotifyTrackArray(unwrapped)
        }.getOrDefault(emptyList())
    }

    override suspend fun resolveStream(
        trackId: String,
        title: String,
        artist: String,
        durationMs: Long,
        preferLossless: Boolean
    ): ResolvedStream? {
        val audioProviders = HybridSourceRegistry.getOrderedActiveProviders(appContext)
            .filter { it.config.id != HybridSourceRegistry.SOURCE_ID_SPOTIFY }
        for (provider in audioProviders) {
            val resolved = provider.resolveStream(
                trackId = trackId,
                title = title,
                artist = artist,
                durationMs = durationMs,
                preferLossless = preferLossless
            )
            if (resolved != null) return resolved
        }
        return null
    }

    private fun parseSpotifyTrackArray(items: JSONArray): List<HybridTrack> {
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val trackId = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artistsArr = item.optJSONArray("artists")
            val artist = if (artistsArr != null && artistsArr.length() > 0) {
                (0 until artistsArr.length()).mapNotNull { artistsArr.optJSONObject(it)?.optString("name") }.joinToString(", ")
            } else "Unknown Artist"
            val albumObj = item.optJSONObject("album")
            val albumName = albumObj?.optString("name").orEmpty()
            val images = albumObj?.optJSONArray("images")
            val coverUrl = images?.optJSONObject(0)?.optString("url").orEmpty()
            HybridTrack(
                canonicalId = "spotify:$trackId",
                rawId = trackId,
                sourceId = config.id,
                title = title,
                artist = artist,
                album = albumName,
                coverUrl = coverUrl,
                durationMs = item.optLong("duration_ms", 0L),
                codecBadge = "Spotify • Hybrid",
                isLossless = false,
                isExplicit = item.optBoolean("explicit", false)
            )
        }
    }

    private fun spotifyGet(url: String, token: String): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3500
            readTimeout = 3500
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun getAccessToken(): String? {
        val (cookie, savedToken, savedExpiry) = HybridSourceRegistry.getSpotifyWebSession(appContext)
        if (savedToken.isNotBlank() && System.currentTimeMillis() < savedExpiry - 15_000L) {
            return savedToken
        }
        if (cachedAccessToken.isNotBlank() && System.currentTimeMillis() < tokenExpiresAtMs - 15_000L) {
            return cachedAccessToken
        }
        return runCatching {
            val conn = (URL("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            }
            if (conn.responseCode !in 200..299) return@runCatching null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val token = json.optString("accessToken")
            val expiry = json.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis() + 1800_000L)
            if (token.isNotBlank()) {
                cachedAccessToken = token
                tokenExpiresAtMs = expiry
                HybridSourceRegistry.saveSpotifyWebSession(appContext, cookie, token, expiry)
                token
            } else null
        }.getOrNull()
    }
}

/**
 * Provider implementation for each installed BitChord-style manifest addon.
 */
class ManifestAddonSourceProvider(
    context: Context,
    override val config: ConfiguredSource
) : AudioSourceProvider {

    private val appContext = context.applicationContext
    private val streamCache = ConcurrentHashMap<String, ResolvedStream>()
    private val negativeCache = ConcurrentHashMap<String, Long>()

    override suspend fun search(query: String, limit: Int): List<HybridTrack> = withContext(Dispatchers.IO) {
        if (!config.enabled || config.baseUrl.isBlank() || query.isBlank()) return@withContext emptyList()
        val token = HybridSourceRegistry.getToken(appContext, config.id).orEmpty()
        val quality = if (config.supportsLossless) "HI_RES_LOSSLESS" else "HIGH"
        runCatching {
            val root = config.baseUrl.trimEnd('/')
            val json = fetchJson("$root/search?q=${encodeQuery(query)}&quality=$quality", token, timeoutMs = 2500)
            val tracks = json.optJSONArray("tracks") ?: return@runCatching emptyList()
            (0 until minOf(tracks.length(), limit)).mapNotNull { i ->
                val obj = tracks.optJSONObject(i) ?: return@mapNotNull null
                val rawId = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artist = obj.optString("artist", "Unknown Artist")
                val durationSec = obj.optDouble("duration", 0.0)
                val codec = obj.optString("codec", if (config.supportsLossless) "FLAC" else "AAC").uppercase()
                HybridTrack(
                    canonicalId = "${config.id}:$rawId",
                    rawId = rawId,
                    sourceId = config.id,
                    title = title,
                    artist = artist,
                    album = obj.optString("album", config.name),
                    coverUrl = obj.optString("coverUrl", obj.optString("artwork", "")),
                    durationMs = if (durationSec > 0) (durationSec * 1000).toLong() else 0L,
                    codecBadge = "$codec • ${config.name}",
                    isLossless = codec in setOf("FLAC", "ALAC", "WAV"),
                    isExplicit = obj.optBoolean("explicit", false)
                )
            }
        }.getOrElse { err ->
            Log.w("KiloAddonSource", "Search failed for ${config.name}: ${err.message}")
            emptyList()
        }
    }

    override suspend fun resolveStream(
        trackId: String,
        title: String,
        artist: String,
        durationMs: Long,
        preferLossless: Boolean
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        if (!config.enabled || config.baseUrl.isBlank()) return@withContext null

        val (prefix, strippedId) = HybridTrack.parsePrefix(trackId)
        val isNativeId = prefix == config.id
        val cacheKey = if (isNativeId) "${config.id}:$strippedId" else "${config.id}:$title|$artist"

        streamCache[cacheKey]?.takeIf { it.expiresAtEpochMs > System.currentTimeMillis() + 10_000L }?.let {
            return@withContext it
        }
        if ((negativeCache[cacheKey] ?: 0L) > System.currentTimeMillis()) return@withContext null

        val token = HybridSourceRegistry.getToken(appContext, config.id).orEmpty()
        val root = config.baseUrl.trimEnd('/')
        val quality = if (preferLossless && config.supportsLossless) "HI_RES_LOSSLESS" else "HIGH"

        runCatching {
            val resolvedNativeId = if (isNativeId) {
                strippedId
            } else {
                if (title.isBlank()) return@runCatching null
                val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                val searchJson = fetchJson("$root/search?q=${encodeQuery(query)}&quality=$quality", token, timeoutMs = 2000)
                val tracks = searchJson.optJSONArray("tracks") ?: return@runCatching null
                val targetSec = durationMs / 1000.0
                (0 until tracks.length()).mapNotNull { tracks.optJSONObject(it) }
                    .filter { t ->
                        val titleOk = normalize(t.optString("title")) == normalize(title)
                        val artistOk = artist.isBlank() || normalize(t.optString("artist")) == normalize(artist)
                        val sec = t.optDouble("duration", -1.0)
                        val durationOk = targetSec <= 0.0 || (sec > 0.0 && abs(sec - targetSec) <= 8.0)
                        titleOk && artistOk && durationOk
                    }
                    .minByOrNull { abs(it.optDouble("duration", targetSec) - targetSec) }
                    ?.optString("id")
                    ?.takeIf { it.isNotBlank() } ?: return@runCatching null
            }

            val streamJson = fetchJson("$root/stream/${encodePath(resolvedNativeId)}?quality=$quality", token, timeoutMs = 2200)
            if (streamJson.optBoolean("encrypted", false)) return@runCatching null

            val codec = streamJson.optString("codec", streamJson.optString("format")).lowercase()
            val streamUrl = streamJson.optString("url")
            val parsedUrl = URL(streamUrl)
            if (parsedUrl.protocol != "https") return@runCatching null

            val sameHost = parsedUrl.host.equals(URL(root).host, ignoreCase = true)
            val headers = if (token.isBlank() || !sameHost) emptyMap() else mapOf("Authorization" to "Bearer $token")

            val sampleRate = streamJson.optInt("sampleRate", 44100)
            val bitDepth = streamJson.optInt("bitDepth", 16)
            val mimeType = when (codec) {
                "flac" -> "audio/flac"
                "alac" -> "audio/alac"
                "opus" -> "audio/webm"
                else -> "audio/mp4"
            }
            val badge = buildString {
                append(codec.uppercase())
                if (bitDepth > 0 && codec in setOf("flac", "alac")) append(" • ${bitDepth}-bit")
                if (sampleRate > 0) append(" • ${sampleRate / 1000.0} kHz")
                append(" • ${config.name}")
            }

            val lease = ResolvedStream(
                url = streamUrl,
                codec = badge,
                userAgent = "KILO/4.0 (Android; Hybrid Audio Client)",
                requestHeaders = headers,
                bitrateKbps = streamJson.optInt("bitrate", 0) / 1000,
                durationMs = durationMs,
                expiresAtEpochMs = System.currentTimeMillis() + 240_000L,
                videoId = trackId,
                clientId = config.id,
                mime = mimeType,
                origin = StreamOrigin.LOSSLESS
            )
            streamCache[cacheKey] = lease
            negativeCache.remove(cacheKey)
            lease
        }.getOrElse {
            negativeCache[cacheKey] = System.currentTimeMillis() + 60_000L
            null
        }
    }

    override fun invalidateTrack(trackId: String) {
        val (_, stripped) = HybridTrack.parsePrefix(trackId)
        streamCache.keys.filter { it.endsWith(":$stripped") }.forEach { streamCache.remove(it) }
    }

    companion object {
        internal fun fetchJson(url: String, token: String, timeoutMs: Int = 2000): JSONObject {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("Addon HTTP ${conn.responseCode}")
                }
                return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }

        private fun normalize(value: String): String = value.lowercase()
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

        private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")
        private fun encodePath(value: String): String = encodeQuery(value).replace("+", "%20")
    }
}