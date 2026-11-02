package com.example.kilo.discovery.smartshuffle

import android.content.SharedPreferences

class DiscoveryFeedbackManager(private val preferences: SharedPreferences) {
    fun keep(trackId: String) = remember("kept", trackId)
    fun remove(trackId: String) = remember("removed", trackId)
    fun neverRecommend(trackId: String) = remember("never", trackId)
    fun keptIds(): Set<String> = read("kept")
    fun removedIds(): Set<String> = read("removed")
    fun neverRecommendIds(): Set<String> = read("never")

    private fun remember(bucket: String, id: String) {
        if (id.isBlank()) return
        val key = "infinite_discovery_feedback_$bucket"
        val values = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        values += id
        preferences.edit().putStringSet(key, values.toList().takeLast(2_000).toSet()).apply()
    }

    private fun read(bucket: String): Set<String> =
        preferences.getStringSet("infinite_discovery_feedback_$bucket", emptySet()).orEmpty().toSet()
}
