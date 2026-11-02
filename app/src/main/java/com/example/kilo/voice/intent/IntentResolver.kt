package com.example.kilo.voice.intent

import com.example.kilo.voice.domain.VoiceCommand
import com.example.kilo.voice.domain.VoiceContext
import com.example.kilo.voice.domain.VoiceIntent
import com.example.kilo.voice.playlist.PlaylistMatcher

object IntentResolver {
    fun resolve(command: VoiceCommand, context: VoiceContext): VoiceCommand {
        if (command.intent != VoiceIntent.PLAY || command.entity.isNullOrBlank()) return command
        val playlist = PlaylistMatcher.bestMatch(command.entity, context.playlistNames)
        return if (playlist != null) command.copy(intent = VoiceIntent.PLAY_PLAYLIST, entity = playlist)
        else command
    }
}
