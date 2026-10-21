package com.example.kilo

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

data class LyricLine(val timeMs: Long, val text: String)

/** Tries multiple lyric providers in order. Network work always runs off the caller's main thread. */
object LyricsRepository {
    private const val TAG = "KiloLyrics"
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<LyricLine>>()

    suspend fun find(song: SongItemClean): List<LyricLine> = withContext(Dispatchers.IO) {
        val key = "${song.title.trim().lowercase()}|${song.artist.trim().lowercase()}"
        cache[key]?.let { return@withContext it }
        // Prefer timestamped sources so the overlay can synchronize and seek. Each provider
        // is isolated: timeouts, API changes, and no-match responses move on to the next one.
        val syncedProviders = listOf<(SongItemClean) -> List<LyricLine>>(
            ::findLrclib,
            ::findKugou
        )
        var result = emptyList<LyricLine>()
        for (provider in syncedProviders) {
            result = runCatching { provider(song) }
                .onFailure { Log.w(TAG, "Timed lyrics provider failed", it) }
                .getOrDefault(emptyList())
            if (result.any { it.timeMs >= 0L }) break
            result = emptyList()
        }
        if (result.isEmpty()) {
            result = runCatching { findLyricsOvh(song) }
                .onFailure { Log.w(TAG, "Plain lyrics fallback failed", it) }
                .getOrDefault(emptyList())
        }
        if (result.isNotEmpty()) cache[key] = result
        result
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
