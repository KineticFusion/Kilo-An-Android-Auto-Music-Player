package com.example.kilo.voice.assistant

import com.example.kilo.AppStateModel
import com.example.kilo.voice.domain.VoiceIntent
import com.example.kilo.voice.intent.IntentParser
import com.example.kilo.voice.playlist.PlaylistMatcher

/** Resolves only clear playlist matches; uncertain names stay on the normal song-search path. */
object AssistantPlaylistBridge {
    fun command(phrase: String): String = phrase.trim()

    fun ambiguousMatches(phrase: String, state: AppStateModel): List<String> {
        val parsed = IntentParser.parse(phrase)
        if (parsed.intent != VoiceIntent.PLAY || parsed.entity.isNullOrBlank()) return emptyList()
        val ranked = (state.savedPlaylists.map { it.name } + state.youtubeMusicPlaylists.map { it.title })
            .distinct()
            .map { it to PlaylistMatcher.score(parsed.entity, it) }
            .filter { it.second >= 0.68f }
            .sortedByDescending { it.second }
        val first = ranked.firstOrNull() ?: return emptyList()
        val close = ranked.filter { it.second >= first.second - 0.08f }
        return if (close.size > 1 && first.second < 1f) close.take(3).map { it.first } else emptyList()
    }

    fun preferExactPlaylist(phrase: String, state: AppStateModel): String? {
        val parsed = IntentParser.parse(phrase)
        if (parsed.intent != VoiceIntent.PLAY || parsed.entity.isNullOrBlank()) return null
        val name = parsed.entity
        val candidates = (state.savedPlaylists.map { it.name } + state.youtubeMusicPlaylists.map { it.title })
            .distinct()
        val matches = candidates.map { it to PlaylistMatcher.score(name, it) }
            .filter { it.second >= 0.88f }
            .sortedByDescending { it.second }
        val first = matches.firstOrNull() ?: return null
        if (matches.size > 1 && matches[1].second >= first.second - 0.04f) return null
        return "play playlist ${first.first}"
    }
}
