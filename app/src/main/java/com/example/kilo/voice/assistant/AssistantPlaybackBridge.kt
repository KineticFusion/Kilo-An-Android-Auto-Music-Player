package com.example.kilo.voice.assistant

import com.example.kilo.voice.intent.IntentParser
import com.example.kilo.voice.domain.VoiceIntent

/** Converts platform media-search requests to KILO's existing voice-command grammar. */
object AssistantPlaybackBridge {
    fun command(query: String): String {
        var clean = query.trim()
            .replace(Regex("^open kilo and\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^open kilo,?\\s+", RegexOption.IGNORE_CASE), "")
        if (clean.isBlank()) return "resume music"
        Regex("^(?:play|start|build) (.+?) radio$", RegexOption.IGNORE_CASE).matchEntire(clean)?.let { match ->
            return "start ${match.groupValues[1]} radio"
        }
        Regex("^shuffle (?!playlist|queue|my songs)(.+)$", RegexOption.IGNORE_CASE).matchEntire(clean)?.let { match ->
            return "shuffle playlist ${match.groupValues[1].trim()}"
        }
        val parsed = IntentParser.parse(clean)
        return when (parsed.intent) {
            VoiceIntent.PLAY, VoiceIntent.PLAY_PLAYLIST, VoiceIntent.PLAY_SAVED_PLAYLIST,
            VoiceIntent.PLAY_DOWNLOADED_PLAYLIST, VoiceIntent.PLAY_LIKED_SONGS,
            VoiceIntent.PLAY_DOWNLOADS, VoiceIntent.PLAY_FAVOURITES, VoiceIntent.PLAY_LIBRARY,
            VoiceIntent.PLAY_NEW_MUSIC, VoiceIntent.PLAY_HIDDEN_GEMS, VoiceIntent.PLAY_DISCOVERY,
            VoiceIntent.PLAY_SIMILAR, VoiceIntent.SURPRISE_ME, VoiceIntent.BUILD_RADIO,
            VoiceIntent.PAUSE, VoiceIntent.RESUME, VoiceIntent.SKIP, VoiceIntent.PREVIOUS,
            VoiceIntent.SHUFFLE, VoiceIntent.SHUFFLE_QUEUE, VoiceIntent.REPEAT_ONE,
            VoiceIntent.REPEAT_ALL, VoiceIntent.REPEAT_OFF -> clean
            else -> "play $clean"
        }
    }
}
