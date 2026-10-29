package com.example.kilo.search.preparation

import com.example.kilo.SongItemClean
import java.util.LinkedHashMap
import java.util.Locale

/** Small process cache for recently repeated song searches. */
object SearchResultCache {
    private const val MAX_QUERIES = 16
    // Keep enough results for the search screen; preparation remains capped separately.
    private const val MAX_RESULTS = 40
    private const val TTL_MS = 15 * 60 * 1000L
    private data class Entry(val songs: List<SongItemClean>, val savedAtMs: Long)
    private val entries = object : LinkedHashMap<String, Entry>(MAX_QUERIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_QUERIES
    }

    fun put(query: String, songs: List<SongItemClean>) = synchronized(entries) {
        val key = normalize(query)
        if (key.isNotBlank()) entries[key] = Entry(songs.distinctBy { it.videoId.ifBlank { it.id } }.take(MAX_RESULTS).toList(), System.currentTimeMillis())
    }

    fun get(query: String): List<SongItemClean>? = synchronized(entries) {
        val key = normalize(query)
        val entry = entries[key] ?: return@synchronized null
        if (System.currentTimeMillis() - entry.savedAtMs > TTL_MS) {
            entries.remove(key)
            null
        } else entry.songs
    }

    private fun normalize(query: String) = query.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
