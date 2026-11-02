package com.example.kilo.lyrics.cache

import android.content.Context
import com.example.kilo.lyrics.models.Lyrics
import com.example.kilo.lyrics.models.LyricsLine
import com.example.kilo.lyrics.models.LyricsMetadata
import com.example.kilo.lyrics.models.WordTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class LyricsDiskCache(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, "lyrics-v1")

    suspend fun get(key: String): Lyrics? = withContext(Dispatchers.IO) {
        if (!directory.isDirectory) return@withContext null
        val file = fileFor(key)
        if (!file.isFile) return@withContext null
        runCatching {
            val root = JSONObject(file.readText())
            val metadata = root.getJSONObject("metadata")
            val lines = root.getJSONArray("lines").toLines()
            Lyrics(
                LyricsMetadata(
                    trackId = metadata.optString("trackId"), title = metadata.optString("title"),
                    artist = metadata.optString("artist"), album = metadata.optString("album"),
                    durationMs = metadata.optLong("durationMs"), language = metadata.optString("language")
                ), lines, root.optLong("fetchedAtMs"), root.optBoolean("isSynced", lines.any { it.startMs >= 0L })
            ).takeIf { it.lines.isNotEmpty() }
        }.getOrNull()
    }

    suspend fun put(key: String, lyrics: Lyrics) = withContext(Dispatchers.IO) {
        runCatching {
            if (!directory.exists() && !directory.mkdirs()) return@runCatching
            val metadata = JSONObject().put("trackId", lyrics.metadata.trackId).put("title", lyrics.metadata.title)
                .put("artist", lyrics.metadata.artist).put("album", lyrics.metadata.album)
                .put("durationMs", lyrics.metadata.durationMs).put("language", lyrics.metadata.language)
            val root = JSONObject().put("metadata", metadata).put("fetchedAtMs", lyrics.fetchedAtMs)
                .put("isSynced", lyrics.isSynced).put("lines", JSONArray().apply {
                    lyrics.lines.forEach { line ->
                        put(JSONObject().put("startMs", line.startMs).put("endMs", line.endMs).put("content", line.content)
                            .put("words", JSONArray().apply {
                                line.words.forEach { word ->
                                    put(JSONObject().put("word", word.word).put("startMs", word.startMs)
                                        .put("endMs", word.endMs).put("estimated", word.estimated))
                                }
                            }))
                    }
                })
            val file = fileFor(key)
            val temporary = File(directory, file.name + ".tmp")
            temporary.writeText(root.toString())
            if (!temporary.renameTo(file)) {
                file.writeText(temporary.readText())
                temporary.delete()
            }
            prune()
        }
    }

    private fun JSONArray.toLines(): List<LyricsLine> = (0 until length()).mapNotNull { index ->
        val row = optJSONObject(index) ?: return@mapNotNull null
        val text = row.optString("content")
        if (text.isBlank()) return@mapNotNull null
        val wordsJson = row.optJSONArray("words") ?: JSONArray()
        LyricsLine(row.optLong("startMs", -1L), row.optLong("endMs", -1L), text,
            (0 until wordsJson.length()).mapNotNull { wordIndex ->
                val word = wordsJson.optJSONObject(wordIndex) ?: return@mapNotNull null
                WordTimestamp(word.optString("word"), word.optLong("startMs"), word.optLong("endMs"), word.optBoolean("estimated"))
            })
    }

    private fun fileFor(key: String): File = File(directory, sha256(key) + ".json")
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun prune() {
        directory.listFiles { file -> file.extension == "json" }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_FILES)
            ?.forEach(File::delete)
    }

    companion object { private const val MAX_FILES = 100 }
}
