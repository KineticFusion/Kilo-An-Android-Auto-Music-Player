package com.example.kilo.discovery.mixes

import android.content.SharedPreferences
import com.example.kilo.SongItemClean
import com.example.kilo.discovery.DiscoveryMix
import com.example.kilo.discovery.discoveryKey
import org.json.JSONArray

class DynamicMixRepository(private val preferences: SharedPreferences) {
    fun cached(mixId: String, catalog: List<SongItemClean>, nowMs: Long, maxAgeMs: Long = 4L * 60 * 60 * 1000): List<SongItemClean>? {
        val stamp = preferences.getLong("infinite_mix_time_$mixId", 0L)
        if (stamp == 0L || nowMs - stamp > maxAgeMs) return null
        val ids = runCatching { JSONArray(preferences.getString("infinite_mix_ids_$mixId", "[]")) }
            .getOrNull()?.let { json -> (0 until json.length()).map { json.optString(it) } } ?: return null
        val byId = catalog.associateBy(::discoveryKey)
        return ids.mapNotNull(byId::get).takeIf { it.isNotEmpty() }
    }

    fun save(mix: DiscoveryMix) {
        val ids = JSONArray().apply { mix.songs.forEach { put(discoveryKey(it)) } }
        preferences.edit().putLong("infinite_mix_time_${mix.id}", mix.generatedAtMs)
            .putString("infinite_mix_ids_${mix.id}", ids.toString()).apply()
    }
}
