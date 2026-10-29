package com.example.kilo.mood.preparation

import com.example.kilo.playback.prepared.PreparedTrackRegistry
import java.util.LinkedHashMap

/** Bounded session index for the latest mood's prepared IDs; track payloads stay in the shared registry. */
object MoodSessionCache {
    private const val MAX_MOODS = 16
    private const val TTL_MS = 15 * 60 * 1_000L
    private data class Entry(val trackIds: List<String>, val savedAtMs: Long)
    private val entries = object : LinkedHashMap<String, Entry>(MAX_MOODS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_MOODS
    }

    fun remember(mood: String, trackIds: List<String>) = synchronized(entries) {
        val key = mood.trim().lowercase().takeIf(String::isNotBlank) ?: return@synchronized
        entries[key] = Entry(trackIds.asSequence().filter(String::isNotBlank).distinct().take(20).toList(), System.currentTimeMillis())
    }

    fun prepared(mood: String): List<MoodPreparedTrack> = synchronized(entries) {
        val key = mood.trim().lowercase()
        val entry = entries[key] ?: return@synchronized emptyList()
        if (System.currentTimeMillis() - entry.savedAtMs > TTL_MS) {
            entries.remove(key)
            return@synchronized emptyList()
        }
        entry.trackIds.mapNotNull(PreparedTrackRegistry::get)
    }
}
