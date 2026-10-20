package com.example.kilo

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object InnerTubeApiService {

    private val paletteColors = listOf(
        Color(0xFF8A2BE2),
        Color(0xFFDA70D6),
        Color(0xFF20B2AA),
        Color(0xFF1E90FF),
        Color(0xFFFF6347),
        Color(0xFF9370DB)
    )

    suspend fun searchSongs(query: String): List<SongItem> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Origin", "https://music.youtube.com")
                setRequestProperty("Referer", "https://music.youtube.com/")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }

            val payload = JSONObject().apply {
                put("query", query)
                put("params", "EgWKAQQAOAFAAUAB")
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240918.01.00")
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
            }

            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
            val responseText = conn.inputStream.bufferedReader().readText()

            val videoIdRegex = Regex("\"videoId\":\"([a-zA-Z0-9_-]{11})\"")
            val videoIds = videoIdRegex.findAll(responseText)
                .map { it.groupValues[1] }
                .distinct()
                .take(10)
                .toList()

            val songs = videoIds.mapIndexed { idx, vId ->
                async { fetchPlayerMetadata(vId, idx) }
            }.awaitAll().filterNotNull()

            if (songs.isNotEmpty()) songs else BITCHORD_CURATED_HITS
        } catch (_: Exception) {
            BITCHORD_CURATED_HITS
        }
    }

    fun fetchPlayerMetadata(videoId: String, colorIdx: Int): SongItem? {
        return try {
            val url = URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12L) gzip")
            }

            val payload = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.56.21")
                        put("androidSdkVersion", 32)
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
            }

            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
            val res = JSONObject(conn.inputStream.bufferedReader().readText())
            val details = res.optJSONObject("videoDetails") ?: return null
            val title = details.optString("title", "Song")
            val author = details.optString("author", "Artist").removeSuffix(" - Topic")
            val lengthSec = details.optLong("lengthSeconds", 210L)

            var cover = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            val thumbs = details.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            if (thumbs != null && thumbs.length() > 0) {
                cover = thumbs.getJSONObject(thumbs.length() - 1).optString("url", cover)
            }

            var streamUrl = ""
            var codecLabel = "Opus • 48.0 kHz • 271 kbps • Stereo"
            val formats = res.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats")
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
                            val kbps = (bitrate / 1000).coerceAtLeast(128)
                            codecLabel = "Opus • 48.0 kHz • $kbps kbps • Stereo"
                        }
                    }
                }
            }

            SongItem(
                id = videoId,
                videoId = videoId,
                title = title,
                artist = author,
                album = "BitChord Direct",
                coverUrl = cover,
                audioUrl = streamUrl,
                themeColor = paletteColors[colorIdx % paletteColors.size],
                durationMs = if (lengthSec > 0) lengthSec * 1000L else 210000L,
                codecInfo = codecLabel,
            )
        } catch (_: Exception) {
            null
        }
    }
}