package com.example.kilo.lyrics.cache

import com.example.kilo.lyrics.models.Lyrics
import java.util.LinkedHashMap

class LyricsMemoryCache(private val capacity: Int = 100) {
    private val entries = object : LinkedHashMap<String, Lyrics>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>?): Boolean = size > capacity
    }
    @Synchronized fun get(key: String): Lyrics? = entries[key]
    @Synchronized fun put(key: String, lyrics: Lyrics) { entries[key] = lyrics }
    @Synchronized fun clear() = entries.clear()
    @Synchronized fun size(): Int = entries.size
}
