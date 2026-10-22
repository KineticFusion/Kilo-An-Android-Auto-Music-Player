package com.example.kilo

import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlin.math.abs
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class LyricLine(val timeMs: Long, val text: String)

/** Tries multiple lyric providers in order. Network work always runs off the caller's main thread. */
object LyricsRepository {
    private const val TAG = "KiloLyrics"
    private const val ORDER_KEY = "lyrics_provider_order"
    data class Provider(val id: String, val title: String)
    val providers = listOf(
        Provider("betterlyrics", "Better Lyrics"),
        Provider("paxsenix", "Paxsenix"),
        Provider("youtube_music", "YouTube Music"),
        Provider("lrclib", "LRCLIB"),
        Provider("kugou", "KuGou"),
        Provider("lyrics_ovh", "Lyrics.ovh")
    )
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<LyricLine>>()

    fun providerOrder(context: Context): List<String> {
        val saved = context.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
            .getString(ORDER_KEY, null)?.split(',').orEmpty()
        return (saved.filter { id -> providers.any { it.id == id } } + providers.map { it.id })
            .distinct()
    }

    fun saveProviderOrder(context: Context, order: List<String>) {
        val normalized = (order.filter { id -> providers.any { it.id == id } } + providers.map { it.id }).distinct()
        context.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE).edit()
            .putString(ORDER_KEY, normalized.joinToString(",")).apply()
        cache.clear()
    }

    suspend fun find(song: SongItemClean): List<LyricLine> = withContext(Dispatchers.IO) {
        val key = "${song.title.trim().lowercase()}|${song.artist.trim().lowercase()}"
        cache[key]?.filter { it.timeMs >= 0L }?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val providerFunctions: Map<String, (SongItemClean) -> List<LyricLine>> = mapOf(
            "betterlyrics" to ::findBetterLyrics,
            "paxsenix" to ::findPaxsenix,
            "youtube_music" to YtRepo::fetchLyrics,
            "lrclib" to ::findLrclib,
            "kugou" to ::findKugou,
            "lyrics_ovh" to ::findLyricsOvh
        )
        for (id in providerOrderForLookup()) {
            val provider = providerFunctions[id] ?: continue
            val result = runCatching { provider(song) }
                .onFailure { Log.w(TAG, "Lyrics provider $id failed", it) }
                .getOrDefault(emptyList())
                .filter { it.timeMs >= 0L && it.text.isNotBlank() }
            if (result.isNotEmpty()) {
                cache[key] = result
                return@withContext result
            }
        }
        emptyList()
    }

    @Volatile private var activeOrder: List<String>? = null

    private fun providerOrderForLookup(): List<String> = activeOrder ?: providers.map { it.id }

    /** Call from the settings screen and at app startup so the saved order controls lookup priority. */
    fun loadProviderOrder(context: Context): List<String> = providerOrder(context).also { activeOrder = it }

    fun updateProviderOrder(context: Context, order: List<String>) {
        saveProviderOrder(context, order)
        activeOrder = providerOrder(context)
    }

    private fun findBetterLyrics(song: SongItemClean): List<LyricLine> {
        if (song.title.isBlank() || song.artist.isBlank()) return emptyList()
        val query = "s=${enc(song.title)}&a=${enc(song.artist)}&al=${enc(song.album)}&d=${song.durationMs.coerceAtLeast(0L) / 1000}"
        val response = request("https://api.betterlyrics.org/getLyrics?$query") as? JSONObject ?: return emptyList()
        return parseTtml(response.optString("ttml").takeIf(String::isNotBlank) ?: response.optString("lyrics"))
    }

    private fun findPaxsenix(song: SongItemClean): List<LyricLine> {
        if (song.title.isBlank()) return emptyList()
        val query = enc(listOf(song.artist, song.title).filter(String::isNotBlank).joinToString(" - "))
        // The public Lyrically API exposes Spotify search and lyrics routes; tolerate its
        // evolving response wrappers and move on cleanly when the hosted service is unavailable.
        val search = runCatching { request("https://lyrics.paxsenix.org/spotify/search?q=$query") }.getOrNull()
            ?: runCatching { request("https://lyrics.paxsenix.org/spotify/search?query=$query") }.getOrNull()
        val spotifyUrl = search?.let(::findSpotifyUrl)
        val candidates = buildList {
            add("https://lyrics.paxsenix.org/spotify/lyrics?query=$query")
            add("https://lyrics.paxsenix.org/spotify/lyrics?q=$query")
            if (spotifyUrl != null) add("https://lyrics.paxsenix.org/spotify/lyrics?url=${enc(spotifyUrl)}")
        }
        for (url in candidates) {
            val payload = runCatching { request(url) as? JSONObject }.getOrNull() ?: continue
            parsePaxLyrics(payload).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return emptyList()
    }

    private fun findSpotifyUrl(root: Any): String? {
        val nodes = mutableListOf<JSONObject>()
        collectObjects(root, nodes)
        for (node in nodes) {
            val url = node.optJSONObject("external_urls")?.optString("spotify")
                ?: node.optString("url").takeIf { it.contains("open.spotify.com/track/") }
            if (!url.isNullOrBlank() && url.contains("open.spotify.com/track/")) return url
            val id = node.optString("id")
            if (id.matches(Regex("[A-Za-z0-9]{22}"))) return "https://open.spotify.com/track/$id"
        }
        return null
    }

    private fun collectObjects(value: Any?, out: MutableList<JSONObject>) {
        when (value) {
            is JSONObject -> {
                out += value
                val keys = value.keys()
                while (keys.hasNext()) collectObjects(value.opt(keys.next()), out)
            }
            is org.json.JSONArray -> for (i in 0 until value.length()) collectObjects(value.opt(i), out)
        }
    }

    private fun parsePaxLyrics(root: JSONObject): List<LyricLine> {
        val lines = root.optJSONArray("lines")
            ?: root.optJSONObject("data")?.optJSONArray("lines")
            ?: root.optJSONObject("lyrics")?.optJSONArray("lines")
        if (lines != null) {
            return (0 until lines.length()).mapNotNull { i ->
                val line = lines.optJSONObject(i) ?: return@mapNotNull null
                val text = line.optString("words", line.optString("text")).trim()
                val tag = line.optString("timeTag", line.optString("time")).trim()
                val time = parseTimeTag(tag)
                if (text.isBlank() || time == null) null else LyricLine(time, text)
            }
        }
        val text = root.optString("lyrics").ifBlank { root.optJSONObject("data")?.optString("lyrics").orEmpty() }
        return parse(text)
    }

    private fun parseTimeTag(value: String): Long? {
        val parts = value.trim().split(':')
        if (parts.size < 2) return value.toLongOrNull()
        val seconds = parts.last().replace(',', '.').toDoubleOrNull() ?: return null
        val minutes = parts[parts.lastIndex - 1].toLongOrNull() ?: return null
        val hours = parts.getOrNull(parts.lastIndex - 2)?.toLongOrNull() ?: 0L
        return ((hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong())
    }

    private fun parseTtml(ttml: String): List<LyricLine> {
        if (ttml.isBlank()) return emptyList()
        return runCatching {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(ttml.reader())
            val output = mutableListOf<LyricLine>()
            var inParagraph = false
            var startMs = -1L
            var text = StringBuilder()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> if (parser.name.equals("p", true)) {
                        inParagraph = true
                        startMs = parseTtmlTime(parser.getAttributeValue(null, "begin").orEmpty())
                        text = StringBuilder()
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inParagraph) text.append(parser.text)
                    XmlPullParser.END_TAG -> if (parser.name.equals("p", true)) {
                        val line = text.toString().replace(Regex("\\s+"), " ").trim()
                        if (line.isNotBlank()) output += LyricLine(startMs, line)
                        inParagraph = false
                    }
                }
                event = parser.next()
            }
            output.sortedBy { it.timeMs }
        }.onFailure { Log.w(TAG, "Could not parse Better Lyrics TTML", it) }.getOrDefault(emptyList())
    }

    private fun parseTtmlTime(value: String): Long {
        val raw = value.trim()
        if (raw.endsWith("ms")) return raw.removeSuffix("ms").toDoubleOrNull()?.toLong() ?: -1L
        if (raw.endsWith("s") && !raw.contains(':')) return raw.removeSuffix("s").toDoubleOrNull()?.times(1000)?.toLong() ?: -1L
        return parseTimeTag(raw) ?: -1L
    }

    private fun findLrclib(song: SongItemClean): List<LyricLine> {
        val duration = (song.durationMs / 1000).toInt()
        val exact = runCatching {
            (request("https://lrclib.net/api/get?artist_name=${enc(song.artist)}&track_name=${enc(song.title)}&duration=$duration") as? JSONObject)
                ?.optString("syncedLyrics").orEmpty()
        }.getOrDefault("")
        val lrc = if (exact.isNotBlank()) exact else {
            val search = request("https://lrclib.net/api/search?track_name=${enc(song.title)}&artist_name=${enc(song.artist)}")
            val rows = search as? org.json.JSONArray ?: org.json.JSONArray()
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                .filter { it.optString("syncedLyrics").isNotBlank() }
                .minByOrNull { abs(it.optInt("duration", duration) - duration) }
                ?.optString("syncedLyrics").orEmpty()
        }
        return parse(lrc)
    }

    /**
     * KuGou's public web endpoints are undocumented and may change or reject requests.
     * This adapter is best-effort and only consumes lyric text; it never resolves audio URLs.
     */
    private fun findKugou(song: SongItemClean): List<LyricLine> {
        if (song.title.isBlank()) return emptyList()
        val keyword = listOf(song.artist, song.title).filter(String::isNotBlank).joinToString(" - ")
        val searchUrl = "https://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=${enc(keyword)}&page=1&pagesize=5"
        val search = request(searchUrl) as? JSONObject ?: return emptyList()
        val candidates = search.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        val expectedDuration = song.durationMs / 1000
        val candidate = (0 until candidates.length()).mapNotNull { candidates.optJSONObject(it) }
            .filter { it.optString("hash").isNotBlank() }
            .maxByOrNull { row ->
                val title = row.optString("songname").trim()
                val artist = row.optString("singername").trim()
                val duration = row.optLong("duration", 0L)
                (if (title.equals(song.title, ignoreCase = true)) 4 else 0) +
                    (if (song.artist.isNotBlank() && artist.contains(song.artist, ignoreCase = true)) 3 else 0) -
                    (if (expectedDuration > 0 && duration > 0) (kotlin.math.abs(duration - expectedDuration) / 15).toInt() else 0)
            } ?: return emptyList()
        val detailUrl = "https://www.kugou.com/yy/index.php?r=play/getdata&hash=${enc(candidate.optString("hash"))}"
        val detail = request(detailUrl) as? JSONObject ?: return emptyList()
        val data = detail.optJSONObject("data") ?: return emptyList()
        val returnedTitle = data.optString("song_name")
        val returnedArtist = data.optString("author_name")
        if (returnedTitle.isNotBlank() && !returnedTitle.equals(song.title, ignoreCase = true)) return emptyList()
        if (song.artist.isNotBlank() && returnedArtist.isNotBlank() &&
            !returnedArtist.contains(song.artist, ignoreCase = true) && !song.artist.contains(returnedArtist, ignoreCase = true)) return emptyList()
        return parse(data.optString("lyrics"))
    }

    /** Lyrics.ovh provides plain text only; use -1 timestamps so the UI won't fake sync or seek. */
    private fun findLyricsOvh(song: SongItemClean): List<LyricLine> {
        if (song.artist.isBlank() || song.title.isBlank()) return emptyList()
        val response = request("https://api.lyrics.ovh/v1/${encPath(song.artist)}/${encPath(song.title)}") as? JSONObject
            ?: return emptyList()
        return response.optString("lyrics").lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { LyricLine(-1L, it) }
            .toList()
    }

    private fun request(url: String): Any {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2200; readTimeout = 3000
            setRequestProperty("User-Agent", "Kilo Android Music Player (lyrics)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) error("Lyrics provider HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return if (body.trimStart().startsWith("[")) org.json.JSONArray(body) else JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun parse(lrc: String): List<LyricLine> {
        val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        return lrc.lineSequence().flatMap { line ->
            val matches = timestamp.findAll(line).toList()
            val text = timestamp.replace(line, "").trim()
            matches.asSequence().map { match ->
                val fraction = match.groupValues[3].let { raw -> when (raw.length) { 1 -> raw.toLongOrNull()?.times(100) ?: 0; 2 -> raw.toLongOrNull()?.times(10) ?: 0; else -> raw.toLongOrNull() ?: 0 } }
                LyricLine((match.groupValues[1].toLong() * 60 + match.groupValues[2].toLong()) * 1000 + fraction, text)
            }
        }.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }.toList()
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun encPath(value: String) = enc(value).replace("+", "%20")
}

@Composable
fun SyncedLyricsOverlay(song: SongItemClean, positionMs: Long, onSeek: (Long) -> Unit, accent: Color) {
    var lines by remember(song.id) { mutableStateOf<List<LyricLine>?>(null) }
    LaunchedEffect(song.id) { lines = LyricsRepository.find(song) }
    val timed = lines.orEmpty().any { it.timeMs >= 0L }
    val current = if (timed) lines.orEmpty().indexOfLast { it.timeMs >= 0L && it.timeMs <= positionMs }.coerceAtLeast(0) else -1
    val listState = rememberLazyListState()
    LaunchedEffect(current) { if (current >= 0) listState.animateScrollToItem(current) }
    when {
        lines == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent) }
        lines!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No synced lyrics found", color = Color.White.copy(alpha = .7f), textAlign = TextAlign.Center) }
        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(lines!!) { index, line ->
                Text(line.text, Modifier.fillMaxWidth().then(if (line.timeMs >= 0L) Modifier.clickable { onSeek(line.timeMs) } else Modifier).padding(horizontal = 18.dp), color = if (index == current) accent else Color.White.copy(alpha = .58f), fontSize = if (index == current) 22.sp else 17.sp, fontWeight = if (index == current) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center)
            }
        }
    }
}
