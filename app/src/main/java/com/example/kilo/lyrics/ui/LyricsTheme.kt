package com.example.kilo.lyrics.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalLyricsAccent = compositionLocalOf { Color(0xFFB879FF) }

@Composable
fun LyricsTheme(accent: Color, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLyricsAccent provides accent, content = content)
