package com.example.kilo.lyrics

import com.example.kilo.SongItemClean
import com.example.kilo.lyrics.models.Lyrics

class LyricsCoordinator {
    suspend fun load(song: SongItemClean): Lyrics? = LyricsManager.find(song)
    fun warm(song: SongItemClean) = LyricsManager.prefetch(song)
}
