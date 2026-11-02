package com.example.kilo.lyrics

import android.content.Context
import com.example.kilo.lyrics.cache.LyricsDatabase
import com.example.kilo.lyrics.cache.LyricsMemoryCache
import com.example.kilo.lyrics.models.Lyrics

object LyricsCache {
    private val memory = LyricsMemoryCache(100)
    @Volatile private var database: LyricsDatabase? = null
    fun initialize(context: Context) { if (database == null) synchronized(this) { if (database == null) database = LyricsDatabase(context.applicationContext) } }
    fun memory(key: String): Lyrics? = memory.get(key)
    suspend fun disk(key: String): Lyrics? = database?.read(key)
    fun putMemory(key: String, lyrics: Lyrics) = memory.put(key, lyrics)
    suspend fun putDisk(key: String, lyrics: Lyrics) { database?.write(key, lyrics) }
    fun clearMemory() = memory.clear()
    fun memorySize(): Int = memory.size()
}
