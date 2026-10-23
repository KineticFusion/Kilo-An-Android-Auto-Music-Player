package com.example.kilo

import android.util.Log
import android.webkit.CookieManager
import androidx.compose.ui.graphics.Color
import com.example.kilo.streaming.StreamInfo
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
import java.util.Locale
import java.util.concurrent.TimeUnit

data class HomeCatalogFetch(val shelves: List<HomeShelf>, val complete: Boolean)

object YtRepo {
    private const val TAG = "YtRepo"
    private const val ORIGIN = "https://music.youtube.com"
    private const val WEB_REMIX_VERSION = "1.20250310.01.00"
    private const val SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    private const val ARTISTS_FILTER = "EgWKAQIgAWoMEA4QChADEAQQCRAF"
    private const val COMMUNITY_PLAYLISTS_FILTER = "Eg-KAQwIABA"
    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    @Volatile
    var userAuthCookie: String = ""

    @Volatile
    private var regionOverride: String = "AUTO"

    fun setRegionCode(code: String) {
        regionOverride = code.uppercase().takeIf { it == "AUTO" || it.matches(Regex("[A-Z]{2}")) } ?: "AUTO"
    }

    private fun regionCode(): String = regionOverride.takeUnless { it == "AUTO" }
        ?: Locale.getDefault().country.uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) }
        ?: "US"

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

    /** Touching the repository runs its existing NewPipe initialization before cold playback. */
    fun warmUp() {
        Log.i(TAG, "resolverInitialized")
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
                val language = Locale.getDefault().language.takeIf { it.matches(Regex("[a-zA-Z]{2,3}")) } ?: "en"
                setRequestProperty("Accept-Language", "$language-${regionCode()},$language;q=0.9")
                setRequestProperty("User-Agent", WEB_UA)
                if (userAuthCookie.isNotEmpty()) {
                    setRequestProperty("Cookie", userAuthCookie)
                    setRequestProperty("X-Goog-AuthUser", "0")
                    buildSapisidAuthorizationHeader(userAuthCookie)?.let { setRequestProperty("Authorization", it) }
                } else {
                    setRequestProperty("Cookie", "SOCS=CAI")
                }
            }
            val language = Locale.getDefault().language.takeIf { it.matches(Regex("[a-zA-Z]{2,3}")) } ?: "en"
            val region = regionCode()
            body.put("context", JSONObject().put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", WEB_REMIX_VERSION)
                put("hl", language)
                put("gl", region)
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

    fun searchArtists(query: String): List<ArtistItem> {
        if (query.isBlank()) return emptyList()
        val filtered = ytMusicPost("search", JSONObject().put("query", query).put("params", ARTISTS_FILTER))
        val candidates = filtered?.let(::parseArtists).orEmpty()
        if (candidates.isNotEmpty()) return candidates
        val fallback = ytMusicPost("search", JSONObject().put("query", query)) ?: return emptyList()
        return parseArtists(fallback).filter { it.subtitle.contains("artist", ignoreCase = true) }
    }

    private fun parseArtists(json: JSONObject): List<ArtistItem> {
        val rows = mutableListOf<JSONObject>()
        collect(json, "musicTwoRowItemRenderer", rows)
        collect(json, "musicResponsiveListItemRenderer", rows)
        return rows.mapNotNull { row ->
            val title = runsText(row.optJSONObject("title")).firstOrNull()
                ?: row.optJSONArray("flexColumns")?.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
                    ?.let { runsText(it).firstOrNull() }
                ?: return@mapNotNull null
            val browseId = row.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?.optString("browseId").orEmpty().ifBlank {
                    row.optJSONObject("navigationItemRenderer")?.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                }
            if (browseId.isBlank()) return@mapNotNull null
            val subtitle = runsText(row.optJSONObject("subtitle")).joinToString("").ifBlank {
                row.optJSONArray("flexColumns")?.optJSONObject(1)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
                    ?.let { runsText(it).joinToString("") }.orEmpty()
            }
            val thumbnail = row.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail") ?: row.optJSONObject("thumbnail")
            if (subtitle.isNotBlank() && !subtitle.contains("artist", ignoreCase = true)) return@mapNotNull null
            ArtistItem(browseId, title, subtitle, bestThumb(thumbnail))
        }.distinctBy { it.id }.take(20)
    }

    fun fetchArtistCatalog(artist: ArtistItem): Triple<ArtistItem, List<SongItemClean>, List<SongItemClean>> {
        val json = ytMusicPost("browse", JSONObject().put("browseId", artist.id)) ?: return Triple(artist, emptyList(), emptyList())
        val refreshedArtist = run {
            val headers = mutableListOf<JSONObject>()
            collect(json, "musicImmersiveHeaderRenderer", headers)
            collect(json, "musicVisualHeaderRenderer", headers)
            val header = headers.firstOrNull()
            val name = runsText(header?.optJSONObject("title")).firstOrNull().orEmpty().ifBlank { artist.name }
            val thumbnail = header?.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
            artist.copy(name = name, coverUrl = bestThumb(thumbnail).ifBlank { artist.coverUrl })
        }

        val albumShelves = mutableListOf<JSONObject>()
        collect(json, "musicCarouselShelfRenderer", albumShelves)
        val albums = albumShelves.flatMapIndexed { shelfIndex, shelf ->
            val header = runsText(shelf.optJSONObject("header")
                ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")?.optJSONObject("title"))
                .firstOrNull().orEmpty()
            if (!header.contains(Regex("album|single|discography|release", RegexOption.IGNORE_CASE))) emptyList()
            else shelf.optJSONArray("contents")?.let { contents ->
                (0 until contents.length()).mapNotNull { index ->
                    contents.optJSONObject(index)?.optJSONObject("musicTwoRowItemRenderer")?.let { parseTwoRowItem(it, shelfIndex + index) }
                }
            }.orEmpty()
        }.filter { it.albumBrowseId.isNotBlank() }.distinctBy { it.albumBrowseId }

        val musicShelves = mutableListOf<JSONObject>()
        collect(json, "musicShelfRenderer", musicShelves)
        val songs = musicShelves.flatMapIndexed { shelfIndex, shelf ->
            val contents = shelf.optJSONArray("contents") ?: return@flatMapIndexed emptyList()
            (0 until contents.length()).mapNotNull { index ->
                val row = contents.optJSONObject(index)?.optJSONObject("musicResponsiveListItemRenderer")
                    ?: return@mapNotNull null
                parseResponsiveItem(row, shelfIndex + index)?.let { song ->
                    song.copy(artist = song.artist.takeUnless { it == "Unknown" }.orEmpty().ifBlank { refreshedArtist.name })
                }
            }
        }.distinctBy { it.videoId }
        return Triple(refreshedArtist, albums, songs)
    }

    fun fetchAlbumTracks(browseId: String, artistName: String): List<SongItemClean> {
        val json = ytMusicPost("browse", JSONObject().put("browseId", browseId)) ?: return emptyList()
        val rows = mutableListOf<JSONObject>()
        collect(json, "musicResponsiveListItemRenderer", rows)
        return rows.mapIndexedNotNull { index, row ->
            parseResponsiveItem(row, index)?.let { song ->
                song.copy(artist = song.artist.takeUnless { it == "Unknown" }.orEmpty().ifBlank { artistName })
            }
        }.distinctBy { it.videoId }
    }

    fun searchCommunityPlaylists(query: String): List<SongItemClean> {
        if (query.isBlank()) return emptyList()
        val filtered = ytMusicPost(
            "search",
            JSONObject().put("query", query).put("params", COMMUNITY_PLAYLISTS_FILTER)
        )
        val filteredResults = filtered?.let(::parseCommunityPlaylistResults).orEmpty()
        if (filteredResults.isNotEmpty()) return filteredResults
        val fallback = ytMusicPost("search", JSONObject().put("query", query)) ?: return emptyList()
        return parseCommunityPlaylistResults(fallback)
    }

    private fun parseCommunityPlaylistResults(json: JSONObject): List<SongItemClean> {
        val twoRowItems = mutableListOf<JSONObject>()
        collect(json, "musicTwoRowItemRenderer", twoRowItems)
        val results = twoRowItems.mapIndexedNotNull { index, row -> parseCommunityPlaylist(row, index) }
        if (results.isNotEmpty()) return results.distinctBy { it.playlistId }

        // Some search responses use compact list renderers for playlists.
        val listItems = mutableListOf<JSONObject>()
        collect(json, "musicResponsiveListItemRenderer", listItems)
        return listItems.mapIndexedNotNull { index, row -> parseCommunityPlaylist(row, index) }
            .distinctBy { it.playlistId }
    }

    private fun parseCommunityPlaylist(row: JSONObject, index: Int): SongItemClean? {
        val flex = row.optJSONArray("flexColumns")
        fun columnText(index: Int): String {
            val text = flex?.optJSONObject(index)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
            return text?.optString("simpleText")?.takeIf(String::isNotBlank)
                ?: runsText(text).joinToString("")
        }
        val titleNode = row.optJSONObject("title")
        val title = titleNode?.optString("simpleText")?.takeIf(String::isNotBlank)
            ?: runsText(titleNode).joinToString("").takeIf(String::isNotBlank)
            ?: columnText(0).takeIf(String::isNotBlank)
            ?: return null

        val navigation = row.optJSONObject("navigationEndpoint")
        val playNavigation = row.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
        val playlistId = sequenceOf(
            navigation?.optJSONObject("watchEndpoint")?.optString("playlistId"),
            navigation?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId"),
            navigation?.optJSONObject("browseEndpoint")?.optString("browseId"),
            playNavigation?.optJSONObject("watchEndpoint")?.optString("playlistId"),
            playNavigation?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId"),
            row.optJSONObject("playlistItemData")?.optString("playlistId")
        ).filterNotNull().firstOrNull { it.isNotBlank() && it != "null" }
            ?.removePrefix("VL")
            ?.takeIf { it.startsWith("PL") || it.startsWith("OLAK5uy_") || it.startsWith("RD") }
            ?: return null
        val subtitleNode = row.optJSONObject("subtitle")
        val subtitle = subtitleNode?.optString("simpleText")?.takeIf(String::isNotBlank)
            ?: runsText(subtitleNode).joinToString("").ifBlank { columnText(1) }
        val thumbnailContainer = row.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
            ?: row.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
        val thumbnail = bestThumb(thumbnailContainer)
        return SongItemClean(
            id = "pl_$playlistId",
            playlistId = playlistId,
            title = title,
            artist = subtitle.ifBlank { "Community playlist" },
            album = "Community playlist",
            coverUrl = thumbnail,
            themeColor = paletteColors[index % paletteColors.size]
        )
    }

    fun fetchHome(): List<HomeShelf> {
        return fetchHomeCatalog().shelves
    }

    /** Loads every continuation page exposed by the signed-in or public Music Home feed. */
    fun fetchHomeCatalog(maxPages: Int = 5): HomeCatalogFetch {
        val shelves = mutableListOf<HomeShelf>()
        var json = ytMusicPost("browse", JSONObject().put("browseId", "FEmusic_home"))
        if (json == null) return HomeCatalogFetch(emptyList(), false)
        var pages = 0
        var complete = false
        while (json != null && pages < maxPages.coerceIn(1, 30)) {
            shelves += parseShelves(json)
            val tokens = mutableListOf<JSONObject>()
            collect(json, "nextContinuationData", tokens)
            val token = tokens.firstOrNull()?.optString("continuation").orEmpty()
            if (token.isEmpty()) { complete = true; break }
            val enc = URLEncoder.encode(token, "UTF-8")
            json = ytMusicPost("browse", JSONObject(), "&ctoken=$enc&continuation=$enc&type=next")
            pages++
        }
        return HomeCatalogFetch(shelves.distinctBy { it.title to it.songs.map(SongItemClean::id) }, complete)
    }

    fun fetchPlaylistTracks(playlistId: String): List<SongItemClean> {
        var json: JSONObject? = ytMusicPost(
            "next",
            JSONObject().put("playlistId", playlistId)
                .put("enablePersistentPlaylistPanel", true)
                .put("isAudioOnly", true)
        ) ?: return emptyList()
        val tracks = mutableListOf<SongItemClean>()
        var pages = 0
        while (json != null && pages < 20) {
            val nodes = mutableListOf<JSONObject>()
            collect(json, "playlistPanelVideoRenderer", nodes)
            nodes.forEach { row ->
                val videoId = row.optString("videoId")
                val title = runsText(row.optJSONObject("title")).firstOrNull()
                if (videoId.isNotBlank() && title != null) {
                    val artist = runsText(row.optJSONObject("longBylineText")).firstOrNull() ?: "Unknown"
                    tracks += SongItemClean(
                        id = videoId, videoId = videoId, title = title, artist = artist, album = "YouTube Music",
                        coverUrl = bestThumb(row.optJSONObject("thumbnail")),
                        themeColor = paletteColors[tracks.size % paletteColors.size],
                        durationMs = parseDuration(runsText(row.optJSONObject("lengthText")).firstOrNull().orEmpty()),
                        isExplicit = row.toString().contains("explicit", ignoreCase = true)
                    )
                }
            }
            val nextTokens = mutableListOf<JSONObject>()
            collect(json, "nextContinuationData", nextTokens)
            val continuation = nextTokens.firstOrNull()?.optString("continuation").orEmpty()
            if (continuation.isBlank()) break
            val encoded = URLEncoder.encode(continuation, "UTF-8")
            json = ytMusicPost(
                "next",
                JSONObject().put("playlistId", playlistId).put("continuation", continuation)
                    .put("enablePersistentPlaylistPanel", true).put("isAudioOnly", true),
                "&ctoken=$encoded&continuation=$encoded&type=next"
            )
            pages++
        }
        return tracks.distinctBy { it.videoId }
    }

    /** Best-effort YouTube Music lyrics lookup via the song's Lyrics browse tab. */
    fun fetchLyrics(song: SongItemClean): List<LyricLine> {
        if (song.videoId.isBlank()) return emptyList()
        val next = ytMusicPost(
            "next",
            JSONObject().put("videoId", song.videoId).put("isAudioOnly", true)
        ) ?: return emptyList()
        val tabs = mutableListOf<JSONObject>()
        collect(next, "tabRenderer", tabs)
        var browseId = tabs.firstNotNullOfOrNull { tab ->
            val title = tab.optJSONObject("title")?.optString("simpleText")
                ?: runsText(tab.optJSONObject("title")).joinToString("")
            if (title.contains("lyrics", ignoreCase = true)) {
                tab.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
            } else null
        }.orEmpty()
        if (browseId.isBlank()) {
            val endpoints = mutableListOf<JSONObject>()
            collect(next, "browseEndpoint", endpoints)
            browseId = endpoints.firstOrNull { it.optString("browseId").startsWith("MPLYt") }
                ?.optString("browseId").orEmpty()
        }
        if (browseId.isBlank()) return emptyList()
        val response = ytMusicPost("browse", JSONObject().put("browseId", browseId)) ?: return emptyList()
        val lyricRows = mutableListOf<JSONObject>()
        collect(response, "lyricLineRenderer", lyricRows)
        return lyricRows.mapNotNull { row ->
            val textNode = row.optJSONObject("text")
            val text = textNode?.optString("simpleText")
                ?: runsText(textNode).joinToString("")
            text.trim().takeIf(String::isNotBlank)?.let { LyricLine(-1L, it) }
        }
    }

    fun fetchStream(videoId: String): StreamInfo? {
        fetchStreamNewPipe(videoId)?.let { return it }
        Log.w(TAG, "NewPipe failed for $videoId, trying legacy clients")
        return fetchStreamLegacy(videoId)
    }

    private fun userAgentFor(url: String): String {
        val client = Regex("[?&]c=([A-Z_0-9]+)").find(url)?.groupValues?.get(1).orEmpty()
        return when (client) {
            "IOS" -> "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
            "ANDROID_VR" -> "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
            "ANDROID" -> "com.google.android.youtube/19.28.35 (Linux; U; Android 14; GB) gzip"
            "TVHTML5" -> "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/75.0.3770.142 Safari/537.36; YouTube; target/smart_tv"
            else -> "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
        }
    }

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
                .filter { it.format?.name?.contains("m4a", ignoreCase = true) == true }
                .maxByOrNull { it.averageBitrate }
                ?: audioStreams.maxByOrNull { it.averageBitrate }
                ?: return null
            val fmt = if (best.format?.name?.lowercase()?.contains("opus") == true) "Opus" else "AAC"
            val kbps = (best.averageBitrate / 1000).coerceAtLeast(128)
            StreamInfo(
                best.content, "$fmt • 48.0 kHz • $kbps kbps • Stereo", userAgentFor(best.content),
                bitrateKbps = kbps
            )
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
                if (bestUrl.isNotEmpty()) return StreamInfo(
                    bestUrl, label, client.userAgent, bitrateKbps = bestBitrate / 1_000
                )
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
        val albumBrowseId = nav?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
            .ifBlank { r.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty() }
        if (videoId.isEmpty() && playlistId.isEmpty() && albumBrowseId.isEmpty()) return null

        val subtitle = runsText(r.optJSONObject("subtitle")).joinToString("").trim()
        val thumb = bestThumb(
            r.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
        )

        return SongItemClean(
            id = videoId.ifEmpty { playlistId.takeIf { it.isNotBlank() }?.let { "pl_$it" } ?: "album_$albumBrowseId" },
            videoId = videoId, playlistId = playlistId,
            albumBrowseId = albumBrowseId,
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
