package com.example.kilo.streaming

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * YouTube Music AudioSourceProvider using InnerTube API.
 * Provides search and stream resolution for the hybrid engine.
 */
class YouTubeMusicSourceProvider(
    context: Context,
    override val config: ConfiguredSource
) : AudioSourceProvider {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "KiloYTMProvider"
        private const val SEARCH_URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
        private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        private const val CLIENT_NAME = "WEB_REMIX"
        private const val CLIENT_VERSION = "1.20240918.01.00"
        private const val PLAYER_CLIENT_NAME = "ANDROID_VR"
        private const val PLAYER_CLIENT_VERSION = "1.56.21"
        private const val DEFAULT_TIMEOUT_MS = 3000
    }

    override suspend fun search(query: String, limit: Int): List<HybridTrack> = withContext(Dispatchers.IO) {
        if (!config.enabled || query.isBlank()) return@withContext emptyList()
        runCatching {
            val payload = buildSearchPayload(query)
            val response = postJson(SEARCH_URL, payload, DEFAULT_TIMEOUT_MS) ?: return@runCatching emptyList()
            parseSearchResults(response, limit)
        }.getOrElse { err ->
            Log.w(TAG, "YouTube Music search failed: ${err.message}")
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
        if (!config.enabled) return@withContext null

        val (_, videoId) = HybridTrack.parsePrefix(trackId)
        val resolvedVideoId = if (videoId.length == 11 && videoId.all { it.isLetterOrDigit() || it in "-_" }) {
            videoId
        } else {
            // Fallback: search by metadata
            if (title.isBlank()) return@withContext null
            search("$title $artist", limit = 1).firstOrNull()?.rawId ?: return@withContext null
        }

        runCatching {
            val streamData = fetchPlayerMetadata(resolvedVideoId) ?: return@runCatching null
            buildResolvedStream(streamData, resolvedVideoId, durationMs)
        }.getOrElse { err ->
            Log.w(TAG, "YouTube Music stream resolve failed for $resolvedVideoId: ${err.message}")
            null
        }
    }

    private fun buildSearchPayload(query: String): JSONObject {
        return JSONObject().apply {
            put("query", query)
            put("params", "EgWKAQQAOAFAAUAB") // Music category filter
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
        }
    }

    private fun postJson(urlString: String, payload: JSONObject, timeoutMs: Int): JSONObject? {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("Referer", "https://music.youtube.com/")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }
        try {
            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
            val responseText = conn.inputStream.bufferedReader().readText()
            return JSONObject(responseText)
        } catch (e: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSearchResults(json: JSONObject, limit: Int): List<HybridTrack> {
        val contents = json.optJSONObject("contents")
        val tabbed = contents?.optJSONObject("tabbedSearchResultsRenderer")?.optJSONArray("tabs")
        if (tabbed == null) return emptyList()

        val videoIds = mutableListOf<String>()
        for (i in 0 until tabbed.length()) {
            val tab = tabbed.optJSONObject(i)
            val sectionList = tab?.optJSONObject("tabRenderer")?.optJSONObject("content")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            if (sectionList != null) {
                for (j in 0 until sectionList.length()) {
                    val section = sectionList.optJSONObject(j)
                    val musicShelf = section?.optJSONObject("musicShelfRenderer")
                    val items = musicShelf?.optJSONArray("contents")
                    if (items != null) {
                        for (k in 0 until items.length()) {
                            val item = items.optJSONObject(k)
                            val musicRow = item?.optJSONObject("musicResponsiveListItemRenderer")
                            val videoId = musicRow?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId") ?: ""
                            if (videoId.isNotBlank()) videoIds.add(videoId)
                        }
                    }
                    if (videoIds.size >= limit) break
                }
            }
            if (videoIds.size >= limit) break
        }

        if (videoIds.isEmpty()) return emptyList()

        // Fetch metadata for each videoId in parallel
        return videoIds.take(limit).mapIndexed { idx, videoId ->
            fetchTrackMetadata(videoId, idx)
        }.filterNotNull()
    }

    private fun fetchTrackMetadata(videoId: String, colorIdx: Int): HybridTrack? {
        val playerData = fetchPlayerMetadata(videoId) ?: return null
        val details = playerData.optJSONObject("videoDetails") ?: return null
        val title = details.optString("title", "Song")
        val author = details.optString("author", "Artist").removeSuffix(" - Topic")
        val lengthSec = details.optLong("lengthSeconds", 210L)

        var cover = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        val thumbs = details.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        if (thumbs != null && thumbs.length() > 0) {
            cover = thumbs.getJSONObject(thumbs.length() - 1).optString("url", cover)
        }

        var streamUrl = ""
        var bitrateKbps = 256
        var codecLabel = "Opus • 48.0 kHz • 256 kbps • Stereo"
        val formats = playerData.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
        if (formats != null) {
            var bestBitrate = 0
            for (i in 0 until formats.length()) {
                val fmt = formats.getJSONObject(i)
                val mime = fmt.optString("mimeType")
                if (mime.startsWith("audio/")) {
                    val candidateUrl = fmt.optString("url")
                    val bitrate = fmt.optInt("bitrate", 0)
                    if (candidateUrl.isNotEmpty() && bitrate >= bestBitrate) {
                        bestBitrate = bitrate
                        streamUrl = candidateUrl
                        bitrateKbps = (bitrate / 1000).coerceAtLeast(128)
                        codecLabel = "Opus • 48.0 kHz • ${bitrateKbps} kbps • Stereo"
                    }
                }
            }
        }

        return HybridTrack(
            canonicalId = "yt:$videoId",
            rawId = videoId,
            sourceId = config.id,
            title = title,
            artist = author,
            album = "YouTube Music",
            coverUrl = cover,
            durationMs = if (lengthSec > 0) lengthSec * 1000L else 210000L,
            codecBadge = "$codecLabel • ${config.name}",
            isLossless = false,
            isExplicit = false
        )
    }

    private fun fetchPlayerMetadata(videoId: String): JSONObject? {
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", PLAYER_CLIENT_NAME)
                    put("clientVersion", PLAYER_CLIENT_VERSION)
                    put("androidSdkVersion", 32)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
        }
        return postJson(PLAYER_URL, payload, DEFAULT_TIMEOUT_MS)
    }

    private fun buildResolvedStream(playerData: JSONObject, videoId: String, durationMs: Long): ResolvedStream? {
        val formats = playerData.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: return null
        var bestBitrate = 0
        var streamUrl = ""
        var mimeType = "audio/webm"
        for (i in 0 until formats.length()) {
            val fmt = formats.getJSONObject(i)
            val mime = fmt.optString("mimeType")
            if (mime.startsWith("audio/")) {
                val candidateUrl = fmt.optString("url")
                val bitrate = fmt.optInt("bitrate", 0)
                if (candidateUrl.isNotEmpty() && bitrate >= bestBitrate) {
                    bestBitrate = bitrate
                    streamUrl = candidateUrl
                    mimeType = mime
                }
            }
        }
        if (streamUrl.isBlank()) return null

        val details = playerData.optJSONObject("videoDetails")
        val title = details?.optString("title") ?: "Song"
        val author = details?.optString("author")?.removeSuffix(" - Topic") ?: "Artist"
        val lengthSec = details?.optLong("lengthSeconds", 0L) ?: 0L
        val actualDurationMs = if (lengthSec > 0) lengthSec * 1000L else durationMs

        val bitrateKbps = (bestBitrate / 1000).coerceAtLeast(128)
        val codecBadge = "Opus • 48.0 kHz • ${bitrateKbps} kbps • Stereo • ${config.name}"

        return ResolvedStream(
            url = streamUrl,
            codec = codecBadge,
            userAgent = "KILO/4.0 (Android; YouTube Music Hybrid)",
            requestHeaders = emptyMap(),
            bitrateKbps = bitrateKbps,
            durationMs = actualDurationMs,
            expiresAtEpochMs = System.currentTimeMillis() + 240_000L, // 4 min lease
            videoId = videoId,
            clientId = config.id,
            mime = mimeType,
            origin = StreamOrigin.YOUTUBE_MUSIC
        )
    }
}