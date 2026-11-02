package com.example.kilo.lyrics.models

data class LyricsMetadata(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val language: String = ""
)
