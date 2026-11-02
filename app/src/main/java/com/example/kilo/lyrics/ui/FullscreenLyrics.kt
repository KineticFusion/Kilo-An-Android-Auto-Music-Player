package com.example.kilo.lyrics.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.kilo.SongItemClean

@Composable
fun FullscreenLyrics(song: SongItemClean, positionMs: Long, accent: androidx.compose.ui.graphics.Color, onSeek: (Long) -> Unit) {
    LyricsTheme(accent) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LyricsScreen(song, positionMs, accent, onSeek)
        }
    }
}
