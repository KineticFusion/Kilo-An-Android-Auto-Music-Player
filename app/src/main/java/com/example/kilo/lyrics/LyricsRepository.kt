package com.example.kilo.lyrics

import android.content.Context
import com.example.kilo.LyricLine
import com.example.kilo.SongItemClean

/** Stable provider boundary for lyrics; network fallback remains in KILO's existing repository. */
object LyricsRepository {
    fun initialize(context: Context) = com.example.kilo.LyricsRepository.loadProviderOrder(context)
    fun cached(song: SongItemClean): List<LyricLine> = com.example.kilo.LyricsRepository.cached(song)
    suspend fun fetch(song: SongItemClean): List<LyricLine> = com.example.kilo.LyricsRepository.find(song)
    fun prefetch(song: SongItemClean) = com.example.kilo.LyricsRepository.prefetch(song)
}
