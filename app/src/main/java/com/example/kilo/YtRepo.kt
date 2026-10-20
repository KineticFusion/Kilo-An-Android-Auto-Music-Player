package com.example.kilo

import android.util.Log
import android.webkit.CookieManager
import androidx.compose.ui.graphics.Color
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo as NPStreamInfo
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object YtRepo {
    private const val TAG = "YtRepo"
    private const val ORIGIN = "https://music.youtube.com"
    private const val WEB_REMIX_VERSION = "1.20250310.01.00"
    private const val SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    @Volatile
    var userAuthCookie: String = ""

    val paletteColors = listOf(
        Color(0xFFFF7043), Color(0xFFAB47BC), Color(0xFF26A69A), Color(0xFF42A5F5), Color(0xFFFFCA28)
    )

    private class StreamClient(
        val name: String,
        val version: String,
        val userAgent: String,
        val clientId: String,
        val extra: Map<String, Any>
    )

    private val streamClients = listOf(
        StreamClient(
            "ANDROID_VR", "1.65.10",
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            "28",
            mapOf(
                "deviceMake" to "Oculus", "deviceModel" to "Quest 3", "osName" to "Android",
                "osVersion" to "12L", "androidSdkVersion" to 32
            )
        ),
        StreamClient(
            "IOS", "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            "5",
            mapOf(
                "deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone",
                "osVersion" to "18.3.2.22D82"
            )
        )
    )

    init {
        try {
            NewPipe.init(
                NewPipeDownloader(
                    OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "NewPipe init failed", e)
        }
    }

    fun refreshCookieFromWebView() {
        try {
            val cookies = CookieManager.getInstance().getCookie(ORIGIN) ?: ""
            if (cookies.isNotEmpty()) {
                userAuthCookie = cookies
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshCookieFromWebView failed", e)
        }
    }

    private fun buildSapisidAuthorizationHeader(cookies: String): String? {
        val sapisid = Regex("(?:^|;\\s*)SAPISID=([^;]+)").find(cookies)?.groupValues?.get(1)
            ?: Regex("__Secure-3PAPISID=([^;]+)").find(cookies)?.groupValues?.get(1)
            ?: return null
        val ts = System.currentTimeMillis() / 1000
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest("$ts $sapisid $ORIGIN".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$sha1"
    }

    private fun ytMusicPost(endpoint: String, body: JSONObject, extraQuery: String = ""): JSONObject? {
        return try {
            val conn = (URL("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false$extraQuery")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Origin", ORIGIN)
                setRequestProperty("Referer", "$ORIGIN/")
                setRequestProperty("X-Origin", ORIGIN)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("User-Agent", WEB_UA)
                if (userAuthCookie.isNotEmpty()) {
                    setRequestProperty("Cookie", userAuthCookie)
                    setRequestProperty("X-Goog-AuthUser", "0")
                    buildSapisidAuthorizationHeader(userAuthCookie)?.let { setRequestProperty("Authorization", it) }
                } else {
                    setRequestProperty("Cookie", "SOCS=CAI")
                }
            }
            body.put("context", JSONObject().put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", WEB_REMIX_VERSION)
                put("hl", "en")
                put("gl", "IN")
            }))
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                Log.e(TAG, "$endpoint HTTP $code: ${text.take(300)}")
                null
            } else JSONObject(text)
        } catch (e: Exception) {
            Log.e(TAG, "$endpoint failed", e)
            null
        }
    }

    fun searchSongs(query: String): List<SongItemClean> {
        var json = ytMusicPost("search", JSONObject().put("query", query).put("params", SONGS_FILTER))
        var songs = json?.let { parseSongs(it) }.orEmpty()
        if (songs.isEmpty()) {
            json = ytMusicPost("search", JSONObject().put("query", query))
            songs = json?.let { parseSongs(it) }.orEmpty()
        }
        return songs
    }

    fun fetchHome(): List<HomeShelf> {
        val shelves = mutableListOf<HomeShelf>()
        var json = ytMusicPost("browse", JSONObject().put("browseId", "FEmusic_home"))
        var pages = 0
        while (json != null && pages < 3) {
            shelves += parseShelves(json)
            val tokens = mutableListOf<JSONObject>()
            collect(json, "nextContinuationData", tokens)
            val token = tokens.firstOrNull()?.optString("continuation").orEmpty()
            if (token.isEmpty()) break
            val enc = URLEncoder.encode(token, "UTF-8")
            json = ytMusicPost("browse", JSONObject(), "&ctoken=$enc&continuation=$enc&type=next")
            pages++
        }
        return shelves
    }

    fun fetchPlaylistTracks(playlistId: String): List<SongItemClean> {
        val json = ytMusicPost(
            "next",
            JSONObject().put("playlistId", playlistId)
                .put("enablePersistentPlaylistPanel", true)
                .put("isAudioOnly", true)
        ) ?: return emptyList()
        val nodes = mutableListOf<JSONObject>()
        collect(json, "playlistPanelVideoRenderer", nodes)
        return nodes.mapIndexedNotNull { idx, r ->
            val vid = r.optString("videoId")
            if (vid.isEmpty()) return@mapIndexedNotNull null
            val title = runsText(r.optJSONObject("title")).firstOrNull() ?: return@mapIndexedNotNull null
            val artist = runsText(r.optJSONObject("longBylineText")).firstOrNull() ?: "Unknown"
            val dur = parseDuration(runsText(r.optJSONObject("lengthText")).firstOrNull().orEmpty())
            SongItemClean(
                id = vid, videoId = vid, title = title, artist = artist, album = "YouTube Music",
                coverUrl = bestThumb(r.optJSONObject("thumbnail")),
                themeColor = paletteColors[idx % paletteColors.size],
                durationMs = dur,
                isExplicit = r.toString().contains("explicit", ignoreCase = true),
            )
        }
    }

    fun fetchStream(videoId: String): StreamInfo? {
        fetchStreamNewPipe(videoId)?.let { return it }
        Log.w(TAG, "NewPipe failed for $videoId, trying legacy clients")
        return fetchStreamLegacy(videoId)
    }

    private fun userAgentFor(url: String = ""): String =
        if (url.isEmpty()) "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
        else "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

    private fun fetchStreamNewPipe(videoId: String): StreamInfo? {
        return try {
            val info = NPStreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val audioStreams = info.audioStreams.orEmpty()
                .filter {
                    (it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod == DeliveryMethod.DASH) &&
                        it.content.isNotEmpty()
                }
            if (audioStreams.isEmpty()) return null
            val best = audioStreams
                .filter { it.format?.name?.contains("opus", ignoreCase = true) == true || it.format?.name?.contains("m4a", ignoreCase = true) == true }
                .maxByOrNull { it.averageBitrate }
                ?: audioStreams.maxByOrNull { it.averageBitrate }
                ?: return null
            val fmt = if (best.format?.name?.lowercase()?.contains("opus") == true) "Opus" else "AAC"
            val kbps = (best.averageBitrate / 1000).coerceAtLeast(128)
            StreamInfo(best.content, "$fmt • 48.0 kHz • $kbps kbps • Stereo", userAgentFor(best.content))
        } catch (e: Exception) {
            Log.e(TAG, "NewPipe stream extract failed for $videoId", e)
            null
        }
    }

    private fun fetchStreamLegacy(videoId: String): StreamInfo? {
        for (client in streamClients) {
            try {
                val conn = (URL("$ORIGIN/youtubei/v1/player?prettyPrint=false")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", client.userAgent)
                    setRequestProperty("X-Goog-Api-Format-Version", "2")
                    setRequestProperty("X-YouTube-Client-Name", client.clientId)
                    setRequestProperty("X-YouTube-Client-Version", client.version)
                }
                val clientJson = JSONObject().apply {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    put("hl", "en")
                    put("gl", "IN")
                    client.extra.forEach { (k, v) -> put(k, v) }
                }
                val payload = JSONObject().apply {
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                    put("context", JSONObject().put("client", clientJson))
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                if (code !in 200..299) {
                    Log.e(TAG, "player(${client.name}) HTTP $code")
                    continue
                }
                val res = JSONObject(text)
                val status = res.optJSONObject("playabilityStatus")?.optString("status")
                if (status != "OK") {
                    Log.e(TAG, "player(${client.name}) status=$status for $videoId")
                    continue
                }
                val formats = res.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: continue
                var bestUrl = ""
                var bestBitrate = -1
                var label = ""
                for (i in 0 until formats.length()) {
                    val f = formats.getJSONObject(i)
                    val mime = f.optString("mimeType")
                    val url = f.optString("url")
                    val br = f.optInt("bitrate", 0)
                    if (mime.startsWith("audio/") && url.isNotEmpty() && br > bestBitrate) {
                        bestBitrate = br
                        bestUrl = url
                        val kbps = (br / 1000).coerceAtLeast(128)
                        label = if (mime.contains("opus")) "Opus • 48.0 kHz • $kbps kbps • Stereo"
                        else "AAC • 44.1 kHz • $kbps kbps • Stereo"
                    }
                }
                if (bestUrl.isNotEmpty()) return StreamInfo(bestUrl, label, client.userAgent)
            } catch (e: Exception) {
                Log.e(TAG, "player(${client.name}) failed", e)
            }
        }
        return null
    }

    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (k == key && v is JSONObject) out.add(v) else collect(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun runsText(obj: JSONObject?): List<String> {
        val runs = obj?.optJSONArray("runs") ?: return emptyList()
        return (0 until runs.length()).map { runs.getJSONObject(it).optString("text") }
    }

    private fun bestThumb(container: JSONObject?): String {
        val arr = container?.optJSONArray("thumbnails") ?: return ""
        if (arr.length() == 0) return ""
        return arr.getJSONObject(arr.length() - 1).optString("url")
            .replace(Regex("=w\\d+-h\\d+.*$"), "=w544-h544")
    }

    private fun parseDuration(s: String): Long {
        if (!Regex("\\d+(:\\d+)+").matches(s)) return 210000L
        return s.split(":").fold(0L) { acc, p -> acc * 60 + p.toLong() } * 1000L
    }

    private fun parseResponsiveItem(r: JSONObject, idx: Int): SongItemClean? {
        val flex = r.optJSONArray("flexColumns") ?: return null
        fun col(i: Int) = flex.optJSONObject(i)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")

        val title = runsText(col(0)).firstOrNull() ?: return null
        var videoId = r.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
        if (videoId.isEmpty()) {
            videoId = r.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")
                ?.optString("videoId").orEmpty()
        }
        if (videoId.isEmpty()) return null

        val meta = runsText(col(1)).joinToString("").split("•").map { it.trim() }
            .filter { it.isNotEmpty() && it != "Song" && it != "Video" }
        val durStr = meta.lastOrNull { Regex("\\d+(:\\d+)+").matches(it) }.orEmpty()
        val nonDur = meta.filter { it != durStr }
        val artist = nonDur.firstOrNull() ?: "Unknown"
        val album = nonDur.getOrNull(1) ?: "YouTube Music"
        val thumb = bestThumb(
            r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
        )

        return SongItemClean(
            id = videoId, videoId = videoId, title = title, artist = artist, album = album,
            coverUrl = thumb.ifEmpty { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
            themeColor = paletteColors[idx % paletteColors.size],
            durationMs = if (durStr.isNotEmpty()) parseDuration(durStr) else 210000L,
            isExplicit = r.toString().contains("explicit", ignoreCase = true),
        )
    }

    private fun parseTwoRowItem(r: JSONObject, idx: Int): SongItemClean? {
        val title = runsText(r.optJSONObject("title")).firstOrNull() ?: return null
        val nav = r.optJSONObject("navigationEndpoint")
        val play = r.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")

        val watch = nav?.optJSONObject("watchEndpoint") ?: play?.optJSONObject("watchEndpoint")
        val videoId = watch?.optString("videoId").orEmpty()
        val playlistId = watch?.optString("playlistId").orEmpty()
            .ifEmpty { play?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId").orEmpty() }
            .ifEmpty { nav?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId").orEmpty() }
        if (videoId.isEmpty() && playlistId.isEmpty()) return null

        val subtitle = runsText(r.optJSONObject("subtitle")).joinToString("").trim()
        val thumb = bestThumb(
            r.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
        )

        return SongItemClean(
            id = videoId.ifEmpty { "pl_$playlistId" },
            videoId = videoId, playlistId = playlistId,
            title = title, artist = subtitle.ifEmpty { "YouTube Music" }, album = "YouTube Music",
            coverUrl = thumb.ifEmpty { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
            themeColor = paletteColors[idx % paletteColors.size],
            isExplicit = r.toString().contains("explicit", ignoreCase = true),
        )
    }

    private fun parseSongs(json: JSONObject): List<SongItemClean> {
        val nodes = mutableListOf<JSONObject>()
        collect(json, "musicResponsiveListItemRenderer", nodes)
        return nodes.mapIndexedNotNull { i, r -> parseResponsiveItem(r, i) }.distinctBy { it.id }
    }

    private fun parseShelves(json: JSONObject): List<HomeShelf> {
        val nodes = mutableListOf<JSONObject>()
        collect(json, "musicCarouselShelfRenderer", nodes)
        return nodes.mapNotNull { shelf ->
            val title = runsText(
                shelf.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                    ?.optJSONObject("title")
            ).firstOrNull() ?: return@mapNotNull null
            val contents = shelf.optJSONArray("contents") ?: return@mapNotNull null
            val songs = mutableListOf<SongItemClean>()
            var isList = false
            for (i in 0 until contents.length()) {
                val c = contents.optJSONObject(i) ?: continue
                c.optJSONObject("musicResponsiveListItemRenderer")?.let { r ->
                    parseResponsiveItem(r, songs.size)?.let { songs += it; isList = true }
                }
                c.optJSONObject("musicTwoRowItemRenderer")?.let { r ->
                    parseTwoRowItem(r, songs.size)?.let { songs += it }
                }
            }
            if (songs.isEmpty()) null else HomeShelf(title, songs.distinctBy { it.id }, isList)
        }
    }
}
