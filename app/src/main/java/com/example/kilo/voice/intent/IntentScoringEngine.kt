package com.example.kilo.voice.intent

import com.example.kilo.voice.domain.VoiceIntent

object IntentScoringEngine {
    fun score(text: String, intent: VoiceIntent): Float {
        val value = text.trim().lowercase()
        if (value.isBlank()) return 0f
        val phrases = when (intent) {
            VoiceIntent.PAUSE -> listOf("pause", "stop music", "hold the music")
            VoiceIntent.RESUME -> listOf("resume", "continue music", "keep playing")
            VoiceIntent.SKIP -> listOf("skip", "next song", "next track")
            VoiceIntent.PREVIOUS -> listOf("previous", "last song", "go back")
            VoiceIntent.PLAY_LIKED_SONGS -> listOf("liked songs", "favourite songs", "favorite songs")
            VoiceIntent.PLAY_DOWNLOADS -> listOf("downloads", "downloaded songs", "offline songs")
            VoiceIntent.SHOW_QUEUE -> listOf("show queue", "what's next", "up next")
            VoiceIntent.SURPRISE_ME -> listOf("surprise me", "pick something for me")
            VoiceIntent.PLAY_DISCOVERY -> listOf("discovery", "discover music")
            VoiceIntent.BUILD_RADIO -> listOf("start radio", "build radio", "play radio")
            VoiceIntent.OPEN_LIBRARY -> listOf("open library", "show my library")
            else -> emptyList()
        }
        return phrases.maxOfOrNull { phrase ->
            when {
                value == phrase -> 1f
                value.contains(phrase) -> 0.93f
                else -> 0f
            }
        } ?: 0.25f
    }
}
