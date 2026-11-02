package com.example.kilo.voice.routing

import android.content.Context
import com.example.kilo.MainViewModel
import com.example.kilo.voice.domain.VoiceCommand
import com.example.kilo.voice.domain.VoiceContext
import com.example.kilo.voice.domain.VoiceExecution
import com.example.kilo.voice.domain.VoiceIntent
import com.example.kilo.voice.intent.IntentParser
import com.example.kilo.voice.intent.VoiceIntentRouter

class VoiceCommandRouter(context: Context, private val viewModel: MainViewModel) {
    private val context = context.applicationContext
    private val intentRouter = VoiceIntentRouter()
    private val executor = VoiceCommandExecutor(viewModel, this.context)
    private var pendingPlaylistName: VoiceCommand? = null

    suspend fun execute(transcript: String): VoiceExecution {
        val state = viewModel.state.value
        val commandContext = VoiceContext(
            currentTitle = state.activeSong.title,
            currentArtist = state.activeSong.artist,
            isPlaying = state.isPlaying,
            isSearching = state.isSearching,
            playlistNames = state.savedPlaylists.map { it.name } + state.youtubeMusicPlaylists.map { it.title },
            downloadedCount = state.downloadedSongs.size,
            likedCount = state.favoriteIds.size
        )
        val parsedFollowUp = IntentParser.parse(transcript)
        val pending = pendingPlaylistName
        val command = when {
            pending == null -> intentRouter.route(transcript, commandContext)
            transcript.trim().lowercase() in setOf("cancel", "never mind", "nevermind", "stop") -> {
                pendingPlaylistName = null
                return VoiceExecution(false, "Playlist creation cancelled.")
            }
            parsedFollowUp.intent == VoiceIntent.UNKNOWN -> {
                pendingPlaylistName = null
                pending.copy(entity = transcript.trim())
            }
            else -> {
                pendingPlaylistName = null
                intentRouter.route(transcript, commandContext)
            }
        }
        if (command.intent == VoiceIntent.CREATE_PLAYLIST && command.entity.isNullOrBlank()) {
            pendingPlaylistName = command
            return VoiceExecution(false, "What should I call the playlist? Say its name, or say ‘cancel’.")
        }
        if (command.intent == VoiceIntent.UNKNOWN) {
            return VoiceExecution(false, "Try ‘play my downloads’, ‘create playlist Road Trip’, ‘add this song to playlist Favourites’, or ‘skip’.")
        }
        if (command.intent in setOf(VoiceIntent.DELETE_PLAYLIST, VoiceIntent.CLEAR_QUEUE, VoiceIntent.DOWNLOAD_PLAYLIST)) {
            val action = when (command.intent) {
                VoiceIntent.DELETE_PLAYLIST -> "Delete playlist ${command.entity.orEmpty()}?"
                VoiceIntent.CLEAR_QUEUE -> "Clear the upcoming songs from your queue? The current song will keep playing."
                else -> "Download every track in playlist ${command.entity.orEmpty()}?"
            }
            return VoiceExecution(false, action, requiresConfirmation = true, command = command)
        }
        return executor.execute(command)
    }

    suspend fun confirm(command: VoiceCommand): VoiceExecution = executor.executeConfirmed(command)
}
