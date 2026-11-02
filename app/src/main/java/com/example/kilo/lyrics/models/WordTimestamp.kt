package com.example.kilo.lyrics.models

data class WordTimestamp(
    val word: String,
    val startMs: Long,
    val endMs: Long,
    val estimated: Boolean = false
)
