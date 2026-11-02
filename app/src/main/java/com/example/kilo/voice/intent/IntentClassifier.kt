package com.example.kilo.voice.intent

import com.example.kilo.voice.domain.VoiceIntent

object IntentClassifier {
    fun classify(text: String): VoiceIntent {
        val value = text.lowercase().trim()
        if (value.isBlank()) return VoiceIntent.UNKNOWN
        val choices = listOf(
            VoiceIntent.PAUSE, VoiceIntent.RESUME, VoiceIntent.SKIP, VoiceIntent.PREVIOUS,
            VoiceIntent.PLAY_LIKED_SONGS, VoiceIntent.PLAY_DOWNLOADS, VoiceIntent.SHOW_QUEUE,
            VoiceIntent.SURPRISE_ME, VoiceIntent.PLAY_DISCOVERY, VoiceIntent.BUILD_RADIO,
            VoiceIntent.OPEN_LIBRARY
        )
        return choices.maxByOrNull { IntentScoringEngine.score(value, it) }
            ?.takeIf { IntentScoringEngine.score(value, it) >= 0.9f }
            ?: when {
                value.startsWith("search ") || value.startsWith("find ") -> VoiceIntent.SEARCH
                value.startsWith("play ") || value.startsWith("start ") -> VoiceIntent.PLAY
                else -> VoiceIntent.UNKNOWN
            }
    }
}
