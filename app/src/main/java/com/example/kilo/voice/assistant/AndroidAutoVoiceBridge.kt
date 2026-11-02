package com.example.kilo.voice.assistant

import com.example.kilo.voice.domain.VoiceIntent
import com.example.kilo.voice.intent.IntentParser

/** Normalizes spoken play/search phrasing before it reaches the existing AA search adapter. */
object AndroidAutoVoiceBridge {
    fun searchQuery(query: String): String {
        val command = IntentParser.parse(query)
        return when (command.intent) {
            VoiceIntent.PLAY_PLAYLIST, VoiceIntent.PLAY_SAVED_PLAYLIST ->
                "playlist:${command.entity.orEmpty().ifBlank { query.trim() }}"
            VoiceIntent.PLAY_DOWNLOADED_PLAYLIST ->
                "downloaded-playlist:${command.entity.orEmpty().ifBlank { query.trim() }}"
            VoiceIntent.PLAY, VoiceIntent.SEARCH,
            VoiceIntent.SAVE_PLAYLIST, VoiceIntent.DOWNLOAD_PLAYLIST -> command.entity.orEmpty().ifBlank { query.trim() }
            VoiceIntent.PLAY_SIMILAR -> command.entity?.let { "songs like $it" }.orEmpty().ifBlank { query.trim() }
            VoiceIntent.PLAY_NEW_MUSIC -> "new music"
            VoiceIntent.PLAY_HIDDEN_GEMS -> "hidden gems"
            VoiceIntent.PLAY_DISCOVERY -> "discovery mix"
            else -> query.trim()
        }
    }
}
