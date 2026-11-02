package com.example.kilo.lyrics.models

data class Lyrics(
    val metadata: LyricsMetadata,
    val lines: List<LyricsLine>,
    val fetchedAtMs: Long = System.currentTimeMillis(),
    val isSynced: Boolean = lines.any { it.startMs >= 0L }
)
