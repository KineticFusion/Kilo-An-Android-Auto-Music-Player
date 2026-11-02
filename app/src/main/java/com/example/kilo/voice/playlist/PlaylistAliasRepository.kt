package com.example.kilo.voice.playlist

import android.content.Context

class PlaylistAliasRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("voice_playlist_aliases", Context.MODE_PRIVATE)

    fun aliases(playlistId: String): Set<String> = preferences.getStringSet(key(playlistId), emptySet())?.toSet().orEmpty()

    fun addAlias(playlistId: String, alias: String) {
        val clean = alias.trim().take(64)
        if (playlistId.isBlank() || clean.isBlank()) return
        preferences.edit().putStringSet(key(playlistId), aliases(playlistId) + clean).apply()
    }

    fun removeAlias(playlistId: String, alias: String) {
        preferences.edit().putStringSet(key(playlistId), aliases(playlistId).filterNot { it.equals(alias, true) }.toSet()).apply()
    }

    private fun key(id: String) = "aliases_${id.hashCode()}"
}
