package com.example.kilo.voice.playlist

import com.example.kilo.MainViewModel
import com.example.kilo.SavedPlaylist
import com.example.kilo.voice.domain.VoiceCommand

class PlaylistCommandHandler(private val viewModel: MainViewModel, private val resolver: PlaylistVoiceResolver) {
    fun find(name: String): SavedPlaylist? = resolver.resolve(name, viewModel.state.value.savedPlaylists)
    fun execute(command: VoiceCommand): String? {
        return when (command.intent) {
            com.example.kilo.voice.domain.VoiceIntent.CREATE_PLAYLIST -> {
                val name = command.entity ?: return "Say the playlist name after ‘create playlist’."
                if (viewModel.state.value.savedPlaylists.any { it.name.equals(name, ignoreCase = true) }) {
                    return "A playlist called $name already exists."
                }
                viewModel.createEmptyPlaylist(name)
                "Created playlist $name."
            }
            com.example.kilo.voice.domain.VoiceIntent.DELETE_PLAYLIST -> {
                val playlist = find(command.entity.orEmpty()) ?: return "I couldn’t find that saved playlist."
                viewModel.deletePlaylist(playlist.id)
                "Deleted ${playlist.name}."
            }
            com.example.kilo.voice.domain.VoiceIntent.RENAME_PLAYLIST -> {
                val playlist = find(command.entity.orEmpty()) ?: return "I couldn’t find that saved playlist."
                val nextName = command.secondaryEntity ?: return "Say the new name after ‘to’."
                if (viewModel.state.value.savedPlaylists.any { it.id != playlist.id && it.name.equals(nextName, ignoreCase = true) }) {
                    return "A playlist called $nextName already exists."
                }
                viewModel.updatePlaylistDetails(playlist.id, nextName, playlist.description, playlist.coverUrl)
                "Renamed ${playlist.name} to $nextName."
            }
            com.example.kilo.voice.domain.VoiceIntent.DUPLICATE_PLAYLIST -> {
                val playlist = find(command.entity.orEmpty()) ?: return "I couldn’t find that saved playlist."
                viewModel.duplicateVoicePlaylist(playlist.id)
                "Made a copy of ${playlist.name}."
            }
            else -> null
        }
    }
}
