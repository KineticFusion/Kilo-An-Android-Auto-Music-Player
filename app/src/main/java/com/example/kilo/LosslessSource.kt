package com.example.kilo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
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

/** Small client for a user's compatible, authorized BitChord-style catalogue module. */
object LosslessSource {
    private const val PREFS = "vybe_app_settings"
    private const val KEY_ALIAS = "kilo_lossless_source_token"
    private const val TAG = "KiloLossless"
    private const val UA = "KILO/1.0 (Android; authorized audio source)"
    private data class Resolved(val value: StreamInfo, val expiresAt: Long)
    private val songs = ConcurrentHashMap<String, SongItemClean>()
    private val resolved = ConcurrentHashMap<String, Resolved>()
    private val misses = ConcurrentHashMap<String, Long>()
    private val skipOnce = ConcurrentHashMap.newKeySet<String>()

    fun register(song: SongItemClean, mediaId: String = song.videoId.ifBlank { song.id }) {
        songs[mediaId] = song
    }

    fun song(mediaId: String): SongItemClean? = songs[mediaId]
    fun root(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("lossless_source_url", "").orEmpty()
    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("lossless_source_enabled", false)
    fun hasToken(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("lossless_source_token", null) != null

    fun disable(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("lossless_source_enabled", false)
            .putString("lossless_source_status", "YouTube Music fallback enabled")
            .apply()
        resolved.clear()
        misses.clear()
    }

    fun testAndSave(context: Context, sourceUrl: String, token: String): String {
        val uri = URL(sourceUrl)
        require(uri.protocol == "https") { "Use a secure HTTPS source URL." }
        require(uri.host.isNotBlank()) { "Enter a valid source URL." }
        val normalizedRoot = sourceUrl.removeSuffix("/manifest.json").trimEnd('/')
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousRoot = prefs.getString("lossless_source_url", "").orEmpty()
        val effectiveToken = token.ifBlank {
            if (previousRoot == normalizedRoot) prefs.getString("lossless_source_token", null)?.let(::decrypt).orEmpty() else ""
        }
        val manifestUrl = if (sourceUrl.endsWith("manifest.json")) sourceUrl else "$sourceUrl/manifest.json"
        val manifest = requestJson(manifestUrl, effectiveToken, timeoutMs = 3500)
        require(manifest.optString("id").isNotBlank()) { "Source manifest has no id." }
        val resources = manifest.optJSONArray("resources")
        require(resources != null && (0 until resources.length()).any { resources.optString(it) == "search" } &&
            (0 until resources.length()).any { resources.optString(it) == "stream" }) {
            "Source must provide search and stream resources."
        }
        if (token.isNotBlank()) prefs.edit().putString("lossless_source_token", encrypt(token)).apply()
        else if (previousRoot != normalizedRoot) prefs.edit().remove("lossless_source_token").apply()
        prefs.edit()
            .putString("lossless_source_url", normalizedRoot)
            .putBoolean("lossless_source_enabled", true)
            .putString("lossless_source_status", "Connected: ${manifest.optString("name", manifest.optString("id"))}")
            .apply()
        resolved.clear()
        misses.clear()
        return "Connected: ${manifest.optString("name", manifest.optString("id"))}"
    }

    fun peek(mediaId: String): StreamInfo? = resolved[mediaId]
        ?.takeIf { it.expiresAt > System.currentTimeMillis() }?.value

    fun invalidate(mediaId: String) {
        resolved.remove(mediaId)
        skipOnce.add(mediaId)
    }

    /** Only returns a source match after metadata and the declared, unencrypted codec pass checks. */
    fun resolve(context: Context, mediaId: String): StreamInfo? {
        peek(mediaId)?.let { return it }
        if (skipOnce.remove(mediaId)) return null
        if (!isEnabled(context)) return null
        if ((misses[mediaId] ?: 0L) > System.currentTimeMillis()) return null
        val song = song(mediaId) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = root(context).trimEnd('/')
        if (root.isBlank()) return null
        val token = prefs.getString("lossless_source_token", null)?.let(::decrypt).orEmpty()
        return try {
            val query = listOf(song.title, song.artist).filter(String::isNotBlank).joinToString(" ")
            val search = requestJson("$root/search?q=${enc(query)}&quality=LOSSLESS", token)
            val tracks = search.optJSONArray("tracks") ?: run {
                misses[mediaId] = System.currentTimeMillis() + 300_000L
                return null
            }
            val targetDuration = song.durationMs / 1000.0
            val candidate = (0 until tracks.length()).mapNotNull { tracks.optJSONObject(it) }
                .filter { track ->
                    val titleMatch = normalize(track.optString("title")) == normalize(song.title)
                    val artistMatch = normalize(track.optString("artist")) == normalize(song.artist)
                    val seconds = track.optDouble("duration", -1.0)
                    titleMatch && artistMatch && seconds > 0 && kotlin.math.abs(seconds - targetDuration) <= 8.0
                }
                .minByOrNull { kotlin.math.abs(it.optDouble("duration") - targetDuration) }
                ?: run {
                    misses[mediaId] = System.currentTimeMillis() + 300_000L
                    return null
                }
            val id = candidate.optString("id").takeIf(String::isNotBlank) ?: run {
                misses[mediaId] = System.currentTimeMillis() + 300_000L
                return null
            }
            val stream = requestJson("$root/stream/${encPath(id)}?quality=LOSSLESS", token)
            val codec = stream.optString("codec", stream.optString("format")).lowercase()
            if (stream.optBoolean("encrypted", false) || codec !in setOf("flac", "alac")) {
                misses[mediaId] = System.currentTimeMillis() + 300_000L
                return null
            }
            val streamUrl = stream.optString("url")
            val parsed = URL(streamUrl)
            if (parsed.protocol != "https") return null
            val mediaHostMatchesSource = parsed.host.equals(URL(root).host, ignoreCase = true)
            val headers = if (token.isBlank() || !mediaHostMatchesSource) emptyMap() else mapOf("Authorization" to "Bearer $token")
            val sampleRate = stream.optInt("sampleRate", 0)
            val bitDepth = stream.optInt("bitDepth", 0)
            val label = buildString {
                append(codec.uppercase())
                if (bitDepth > 0) append(" • ${bitDepth}-bit")
                if (sampleRate > 0) append(" • ${sampleRate / 1000.0} kHz")
                append(" • Lossless")
            }
            StreamInfo(streamUrl, label, UA, headers).also {
                resolved[mediaId] = Resolved(it, System.currentTimeMillis() + 240_000L)
                misses.remove(mediaId)
            }
        } catch (error: Exception) {
            misses[mediaId] = System.currentTimeMillis() + 60_000L
            Log.w(TAG, "Lossless lookup unavailable; using YouTube fallback", error)
            null
        }
    }

    private fun requestJson(url: String, token: String, timeoutMs: Int = 1800): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("Source HTTP ${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun encPath(value: String): String = enc(value).replace("+", "%20")

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
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
