package com.example.kilo.lyrics

import android.content.Context
import android.util.Log
import com.example.kilo.SongItemClean
import com.example.kilo.lyrics.models.Lyrics
import com.example.kilo.lyrics.models.LyricsLine
import com.example.kilo.lyrics.models.LyricsMetadata
import com.example.kilo.LyricsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object LyricsManager {
    private const val TAG = "KiloLyrics"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun initialize(context: Context) {
        LyricsManagerContext.applicationContext = context.applicationContext
        LyricsCache.initialize(context.applicationContext)
    }

    suspend fun find(song: SongItemClean): Lyrics? = withContext(Dispatchers.IO) {
        val appContext = LyricsManagerContext.applicationContext
        if (appContext != null && !LyricsSettings.enabled(appContext)) return@withContext null
        val key = key(song)
        val started = android.os.SystemClock.elapsedRealtime()
        LyricsCache.memory(key)?.let { LyricsMetrics.lookup(song.id, "memory", started); return@withContext it }
        LyricsCache.disk(key)?.let {
            LyricsCache.putMemory(key, it)
            LyricsMetrics.lookup(song.id, "disk", started)
            return@withContext it
        }
        val lines = LyricsRepository.find(song)
        if (lines.isEmpty()) {
            LyricsMetrics.lookup(song.id, "miss", started)
            return@withContext null
        }
        var parsedLines = LyricsParser.withEstimatedWords(LyricsParser.fromProvider(lines, song.durationMs))
        // Translate lyrics if enabled
        if (appContext != null && LyricsRepository.isTranslateEnabled(appContext)) {
            val translated = LyricsRepository.translateLyrics(parsedLines)
            if (translated.isNotEmpty()) {
                parsedLines = translated
            }
        }
        val result = Lyrics(
            LyricsMetadata(song.videoId.ifBlank { song.id }, song.title, song.artist, song.album, song.durationMs),
            parsedLines
        )
        LyricsCache.putMemory(key, result)
        LyricsManagerContext.applicationContext?.takeIf { LyricsSettings.offlineCache(it) }?.let {
            LyricsCache.putDisk(key, result)
        }
        LyricsMetrics.loaded(song.id, "provider", android.os.SystemClock.elapsedRealtime() - started, result.lines.size)
        result
    }

    fun prefetch(song: SongItemClean) {
        val appContext = LyricsManagerContext.applicationContext
        if (appContext != null && !LyricsSettings.enabled(appContext)) return
        val key = key(song)
        if (LyricsCache.memory(key) != null) return
        scope.launch {
            runCatching { find(song) }.onFailure { Log.d(TAG, "background lyrics fetch skipped", it) }
        }
    }

    fun cached(song: SongItemClean): Lyrics? {
        val appContext = LyricsManagerContext.applicationContext
        if (appContext != null && !LyricsSettings.enabled(appContext)) return null
        return LyricsCache.memory(key(song))
        ?: LyricsRepository.cached(song).takeIf { it.isNotEmpty() }?.let { lines ->
            val parsedLines = LyricsParser.withEstimatedWords(LyricsParser.fromProvider(lines, song.durationMs))
            // Note: Translation is not applied here since it requires suspend context.
            // Full translation happens in find() which is suspend.
            Lyrics(
                LyricsMetadata(song.videoId.ifBlank { song.id }, song.title, song.artist, song.album, song.durationMs),
                parsedLines
            ).also { LyricsCache.putMemory(key(song), it) }
        }
    }

    suspend fun persistOffline(song: SongItemClean, lyrics: Lyrics) {
        val app = LyricsManagerContext.applicationContext ?: return
        LyricsCache.initialize(app)
        LyricsCache.putMemory(key(song), lyrics)
        LyricsCache.putDisk(key(song), lyrics)
    }

    private fun key(song: SongItemClean): String = listOf(
        song.videoId.ifBlank { song.id }.trim(), song.title.trim().lowercase(),
        song.artist.trim().lowercase(), song.album.trim().lowercase(), song.durationMs.toString()
    ).joinToString("|")
}

private object LyricsManagerContext {
    @Volatile var applicationContext: Context? = null
}
