package com.example.kilo.playback.resume

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Stores only resume metadata; full track metadata remains in the existing SessionStore. */
class LastSessionRepository(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("kilo_background_session_v1", Context.MODE_PRIVATE)

    fun save(snapshot: PlaybackSnapshot) {
        val ids = JSONArray().apply { snapshot.queue.mediaIds.forEach { put(it) } }
        val json = JSONObject()
            .put("ids", ids)
            .put("index", snapshot.queue.currentIndex)
            .put("currentId", snapshot.queue.currentMediaId)
            .put("positionMs", snapshot.positionMs.coerceAtLeast(0L))
            .put("playWhenReady", snapshot.playWhenReady)
            .put("shuffle", snapshot.shuffleEnabled)
            .put("repeat", snapshot.repeatMode)
            .put("savedAt", snapshot.savedAtEpochMs)
        preferences.edit().putString(KEY_SNAPSHOT, json.toString()).apply()
    }

    fun load(): PlaybackSnapshot? = runCatching {
        val json = preferences.getString(KEY_SNAPSHOT, null)?.let(::JSONObject) ?: return null
        val idsJson = json.optJSONArray("ids") ?: JSONArray()
        val ids = (0 until idsJson.length()).mapNotNull { idsJson.optString(it).takeIf(String::isNotBlank) }
        val currentId = json.optString("currentId").takeIf(String::isNotBlank).orEmpty()
        if (ids.isEmpty() || currentId.isBlank()) return null
        PlaybackSnapshot(
            queue = QueueSnapshot(
                mediaIds = ids,
                currentIndex = json.optInt("index", 0).coerceIn(0, ids.lastIndex),
                currentMediaId = currentId
            ),
            positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
            playWhenReady = json.optBoolean("playWhenReady", false),
            shuffleEnabled = json.optBoolean("shuffle", false),
            repeatMode = json.optInt("repeat", 0).coerceIn(0, 2),
            savedAtEpochMs = json.optLong("savedAt", 0L)
        )
    }.getOrNull()

    fun clear() {
        preferences.edit().remove(KEY_SNAPSHOT).apply()
    }

    private companion object { const val KEY_SNAPSHOT = "last_session" }
}
