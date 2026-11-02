package com.example.kilo.lyrics

import com.example.kilo.SongItemClean
import com.example.kilo.lyrics.models.Lyrics

object LyricsDownloader {
    suspend fun saveForOffline(song: SongItemClean): Lyrics? {
        val lyrics = LyricsManager.find(song) ?: return null
        LyricsManager.persistOffline(song, lyrics)
        return lyrics
    }
}
