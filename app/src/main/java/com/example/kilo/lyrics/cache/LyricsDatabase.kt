package com.example.kilo.lyrics.cache

import android.content.Context
import com.example.kilo.lyrics.models.Lyrics

/** Small app-private persistent lyrics store; disk reads/writes are always dispatched to IO. */
class LyricsDatabase(context: Context) {
    private val disk = LyricsDiskCache(context)
    suspend fun read(key: String): Lyrics? = disk.get(key)
    suspend fun write(key: String, value: Lyrics) = disk.put(key, value)
}
