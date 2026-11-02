package com.example.kilo.lyrics.karaoke

import com.example.kilo.lyrics.models.LyricsLine

object KaraokeEngine {
    fun activeWordIndex(line: LyricsLine, positionMs: Long): Int =
        line.words.indexOfLast { positionMs >= it.startMs && positionMs < it.endMs }
}
