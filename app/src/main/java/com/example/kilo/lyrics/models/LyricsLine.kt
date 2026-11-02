package com.example.kilo.lyrics.models

data class LyricsLine(
    val startMs: Long,
    val endMs: Long,
    val content: String,
    val words: List<WordTimestamp> = emptyList()
)
