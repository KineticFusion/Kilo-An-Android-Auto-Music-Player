package com.example.kilo.lyrics

import com.example.kilo.lyrics.models.Lyrics
import com.example.kilo.lyrics.models.LyricsLine

object SyncedLyricsEngine {
    fun activeLine(lyrics: Lyrics, positionMs: Long): Int {
        var low = 0
        var high = lyrics.lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lyrics.lines[mid].startMs in 0..positionMs) { result = mid; low = mid + 1 } else high = mid - 1
        }
        return result
    }
    fun activeWord(line: LyricsLine, positionMs: Long): Int = line.words.indexOfLast { positionMs >= it.startMs && positionMs < it.endMs }
}
