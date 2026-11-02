package com.example.kilo.voice.routing

import com.example.kilo.*
import com.example.kilo.voice.analytics.VoiceCommandMetrics
import com.example.kilo.voice.discovery.DiscoveryVoiceController
import com.example.kilo.voice.domain.VoiceCommand
import com.example.kilo.voice.domain.VoiceExecution
import com.example.kilo.voice.domain.VoiceIntent
import com.example.kilo.voice.library.DownloadResolver
import com.example.kilo.voice.library.LikedSongsResolver
import com.example.kilo.voice.library.FavouritesResolver
import com.example.kilo.voice.playback.VoicePlaybackController
import com.example.kilo.voice.playlist.PlaylistCommandHandler
import com.example.kilo.voice.playlist.PlaylistMatcher
import com.example.kilo.voice.playlist.PlaylistVoiceResolver
import com.example.kilo.voice.queue.QueueCommandHandler
import com.example.kilo.voice.queue.QueueVoiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VoiceCommandExecutor(private val viewModel: MainViewModel, context: android.content.Context) {
    private val playback = VoicePlaybackController(viewModel)
    private val queue = QueueCommandHandler(QueueVoiceController(viewModel))
    private val discovery = DiscoveryVoiceController(viewModel)
    private val liked = LikedSongsResolver()
    private val favourites = FavouritesResolver(liked)
    private val downloads = DownloadResolver()
    private val playlistResolver = PlaylistVoiceResolver(com.example.kilo.voice.playlist.PlaylistAliasRepository(context))
    private val playlistCommands = PlaylistCommandHandler(viewModel, playlistResolver)

    suspend fun execute(command: VoiceCommand): VoiceExecution {
        val started = android.os.SystemClock.elapsedRealtime()
        val result = executeAction(command)
        VoiceCommandMetrics.record(command, result, android.os.SystemClock.elapsedRealtime() - started)
        return result
    }

    suspend fun executeConfirmed(command: VoiceCommand): VoiceExecution = execute(command)

    private suspend fun executeAction(command: VoiceCommand): VoiceExecution {
        val state = viewModel.state.value
        val entity = command.entity.orEmpty()
        val result = when (command.intent) {
            VoiceIntent.PLAY -> playQuery(entity)
            VoiceIntent.PLAY_PLAYLIST, VoiceIntent.PLAY_SAVED_PLAYLIST -> playNamed(entity, shuffle = command.enabled == true)
            VoiceIntent.PLAY_DOWNLOADED_PLAYLIST -> playDownloadedPlaylist(entity)
            VoiceIntent.PLAY_RECENT_PLAYLIST -> {
                val recent = state.savedPlaylists.maxByOrNull { it.updatedAtMs }
                if (recent != null) playSaved(recent.name, recent.id) else completed(false, "You don’t have a saved playlist yet.")
            }
            VoiceIntent.PLAY_LIKED_SONGS -> {
                val songs = liked.resolve().filter { it.videoId.isNotBlank() || it.isDownloaded }
                playSongs(songs, "Liked songs")
            }
            VoiceIntent.PLAY_FAVOURITES -> playSongs(favourites.resolve(), "Favourites")
            VoiceIntent.PLAY_DOWNLOADS -> playSongs(downloads.resolve(state), "Downloads")
            VoiceIntent.PLAY_LIBRARY -> {
                val songs = librarySongs(state)
                playSongs(songs, "your Library")
            }
            VoiceIntent.OPEN_LIBRARY -> {
                viewModel.setScreen(ScreenNav.LIBRARY)
                completed(true, "Opening your Library.")
            }
            VoiceIntent.OPEN_DOWNLOADS -> {
                viewModel.setScreen(ScreenNav.OFFLINE)
                completed(true, "Opening Downloads.")
            }
            VoiceIntent.PLAY_RECENTLY_ADDED -> {
                val recentPlaylist = state.savedPlaylists.maxByOrNull { it.updatedAtMs }
                val recent = (recentPlaylist?.songs.orEmpty() + downloads.resolve(state)).distinctBy { it.videoId.ifBlank { it.id } }
                playSongs(recent, "Recently added")
            }
            VoiceIntent.PLAY_MOST_PLAYED, VoiceIntent.PLAY_LEAST_PLAYED -> playByHistory(state, command.intent == VoiceIntent.PLAY_MOST_PLAYED)
            VoiceIntent.CREATE_PLAYLIST, VoiceIntent.DELETE_PLAYLIST, VoiceIntent.RENAME_PLAYLIST,
            VoiceIntent.DUPLICATE_PLAYLIST -> {
                val message = playlistCommands.execute(command) ?: "That playlist action isn’t available."
                val failed = message.startsWith("I couldn’t", true) ||
                    message.startsWith("A playlist called", true) ||
                    message.startsWith("Say the playlist name", true) ||
                    message.startsWith("Say the new name", true)
                completed(!failed, message)
            }
            VoiceIntent.ADD_TO_PLAYLIST -> addToPlaylist(entity, command.secondaryEntity)
            VoiceIntent.REMOVE_FROM_PLAYLIST -> removeFromPlaylist(entity, command.secondaryEntity)
            VoiceIntent.DOWNLOAD_PLAYLIST -> downloadPlaylist(entity)
            VoiceIntent.SAVE_PLAYLIST -> savePlaylist(entity)
            VoiceIntent.SHOW_QUEUE, VoiceIntent.CLEAR_QUEUE, VoiceIntent.SHUFFLE_QUEUE,
            VoiceIntent.REPEAT_QUEUE, VoiceIntent.ADD_NEXT, VoiceIntent.ADD_TO_QUEUE,
            VoiceIntent.MOVE_TO_TOP, VoiceIntent.MOVE_TO_BOTTOM, VoiceIntent.REMOVE_TRACK -> {
                val message = queue.execute(command)
                completed(message != null && !message.startsWith("I couldn’t"), message ?: "Queue command wasn’t understood.")
            }
            VoiceIntent.PLAY_NEW_MUSIC, VoiceIntent.PLAY_HIDDEN_GEMS, VoiceIntent.PLAY_DISCOVERY,
            VoiceIntent.PLAY_SIMILAR, VoiceIntent.SURPRISE_ME -> completed(true, discovery.execute(command) ?: "Opening music discovery.")
            VoiceIntent.BUILD_RADIO -> {
                val message = discovery.buildRadio(entity.takeIf(String::isNotBlank))
                completed(!message.startsWith("I couldn’t", true), message)
            }
            VoiceIntent.OPEN_HOME -> { viewModel.setScreen(ScreenNav.HOME); completed(true, "Opening Home.") }
            VoiceIntent.OPEN_BROWSE -> { viewModel.setScreen(ScreenNav.EXPLORE); completed(true, "Opening Browse.") }
            VoiceIntent.OPEN_RADIO -> { viewModel.setScreen(ScreenNav.RADIO); completed(true, "Opening Radio.") }
            VoiceIntent.OPEN_SEARCH, VoiceIntent.SEARCH -> {
                viewModel.setScreen(ScreenNav.SEARCH)
                if (entity.isNotBlank()) viewModel.onSearchQueryChanged(entity)
                completed(true, if (entity.isBlank()) "Opening Search." else "Searching for $entity.")
            }
            VoiceIntent.OPEN_SETTINGS -> { viewModel.setScreen(ScreenNav.SETTINGS); completed(true, "Opening Settings.") }
            VoiceIntent.OPEN_ARTISTS -> { viewModel.setScreen(ScreenNav.ARTISTS); completed(true, "Opening Artists.") }
            else -> {
                val message = playback.execute(command)
                if (message != null) completed(true, message)
                else completed(false, "I didn’t understand that. Try ‘play my downloads’, ‘create playlist Road Trip’, or ‘skip’.")
            }
        }
        return result
    }

    private suspend fun playNamed(name: String, shuffle: Boolean = false): VoiceExecution {
        if (name.isBlank()) return completed(false, "What would you like me to play?")
        val state = viewModel.state.value
        val saved = playlistResolver.resolve(name, state.savedPlaylists)
        if (saved != null) return playSaved(saved.name, saved.id, shuffle)
        val remote = findRemotePlaylist(name)
        if (remote != null) {
            val playlistId = remote.playlistId.ifBlank { remote.id }
            val tracks = withContext(Dispatchers.IO) {
                runCatching { YtRepo.fetchPlaylistTracks(playlistId) }.getOrDefault(emptyList())
            }.filter { it.videoId.isNotBlank() || it.isDownloaded }
                .distinctBy { it.videoId.ifBlank { it.id } }
            if (tracks.isEmpty()) return completed(false, "I found ${remote.title}, but it has no playable tracks right now.")
            val playbackTracks = if (shuffle) tracks.shuffled() else tracks
            viewModel.playSongFromShelf(playbackTracks.first(), playbackTracks, "phone_voice_playlist")
            return completed(true, "Playing ${remote.title}.")
        }
        return completed(false, "I couldn’t find a playlist called $name.")
    }

    private fun playQuery(query: String): VoiceExecution {
        if (query.isBlank()) return completed(false, "What would you like me to play?")
        viewModel.playFromAssistantQuery(query)
        return completed(true, "Finding $query and starting the closest match.")
    }

    private fun playSaved(name: String, id: String, shuffle: Boolean = false): VoiceExecution {
        val saved = viewModel.state.value.savedPlaylists.firstOrNull { it.id == id }
            ?: return completed(false, "I couldn’t find $name in your Library.")
        return playSongs(saved.songs, saved.name, shuffle)
    }

    private fun playDownloadedPlaylist(name: String): VoiceExecution {
        val saved = playlistResolver.resolve(name, viewModel.state.value.savedPlaylists)
            ?: return completed(false, "I couldn’t find that downloaded playlist.")
        val local = saved.songs.filter { it.isDownloaded }
        return playSongs(local, saved.name)
    }

    private fun playSongs(songs: List<SongItemClean>, label: String, shuffle: Boolean = false): VoiceExecution {
        val ordered = if (shuffle) songs.shuffled() else songs
        val playable = ordered.filter { it.videoId.isNotBlank() || it.isDownloaded }
            .distinctBy { it.videoId.ifBlank { it.id } }
        val first = playable.firstOrNull() ?: return completed(false, "$label has no playable songs yet.")
        viewModel.playSongFromShelf(first, playable, "phone_voice_${label.lowercase().replace(' ', '_')}")
        return completed(true, "Playing $label.")
    }

    private suspend fun librarySongs(state: AppStateModel): List<SongItemClean> {
        val likedSongs = liked.resolve()
        return (likedSongs + downloads.resolve(state) + state.savedPlaylists.flatMap { it.songs })
            .filter { it.videoId.isNotBlank() || it.isDownloaded }
            .distinctBy { it.videoId.ifBlank { it.id } }
    }

    private suspend fun playByHistory(state: AppStateModel, mostPlayed: Boolean): VoiceExecution {
        val candidates = (state.quickPicks + state.playlist + state.downloadedSongs + state.savedPlaylists.flatMap { it.songs } +
            state.homeShelves.flatMap { it.songs } + liked.resolve())
            .filter { it.videoId.isNotBlank() || it.isDownloaded }
            .distinctBy { it.videoId.ifBlank { it.id } }
        if (candidates.isEmpty()) return completed(false, "Play a few songs first so KILO can build your listening history.")
        val events = withContext(Dispatchers.IO) { PlayedSongHistory.listeningEvents(1000) }
        val counts = events.groupingBy { it.trackId }.eachCount()
        val ordered = if (mostPlayed) {
            candidates.sortedByDescending { counts[it.id] ?: counts[it.videoId] ?: 0 }
        } else {
            candidates.sortedBy { counts[it.id] ?: counts[it.videoId] ?: 0 }
        }
        return playSongs(ordered, if (mostPlayed) "Most played" else "Least played")
    }

    private suspend fun addToPlaylist(name: String, trackQuery: String?): VoiceExecution {
        val state = viewModel.state.value
        val playlist = playlistResolver.resolve(name, state.savedPlaylists)
            ?: return completed(false, "I couldn’t find a playlist called $name.")
        val song = if (trackQuery.isNullOrBlank()) {
            state.activeSong.takeIf { it.id.isNotBlank() && (it.videoId.isNotBlank() || it.isDownloaded) }
        } else {
            val visible = (state.playlist + state.quickPicks + state.downloadedSongs + state.homeShelves.flatMap { it.songs } + liked.resolve())
                .distinctBy { it.videoId.ifBlank { it.id } }
            visible.firstOrNull { it.title.contains(trackQuery, true) || trackQuery.contains(it.title, true) }
                ?: withContext(Dispatchers.IO) { runCatching { YtRepo.searchSongs(trackQuery).firstOrNull { it.videoId.isNotBlank() } }.getOrNull() }
        } ?: return completed(false, if (trackQuery.isNullOrBlank()) "There isn’t a current song to add." else "I couldn’t find a playable match for $trackQuery.")
        viewModel.addSongToPlaylist(playlist.id, song)
        return completed(true, "Added ${song.title} to ${playlist.name}.")
    }

    private fun removeFromPlaylist(name: String, trackQuery: String?): VoiceExecution {
        val state = viewModel.state.value
        val playlist = playlistResolver.resolve(name, state.savedPlaylists)
            ?: return completed(false, "I couldn’t find a playlist called $name.")
        val song = if (trackQuery.isNullOrBlank()) state.activeSong else playlist.songs.firstOrNull {
            it.title.contains(trackQuery, true) || trackQuery.contains(it.title, true)
        }
        if (song == null || song.id.isBlank()) return completed(false, if (trackQuery.isNullOrBlank()) "There isn’t a current song to remove." else "I couldn’t find $trackQuery in ${playlist.name}.")
        viewModel.removeSongFromPlaylist(playlist.id, song.videoId.ifBlank { song.id })
        return completed(true, "Removed ${song.title} from ${playlist.name}.")
    }

    private suspend fun downloadPlaylist(name: String): VoiceExecution {
        val saved = playlistResolver.resolve(name, viewModel.state.value.savedPlaylists)
        if (saved != null) {
            viewModel.downloadVoicePlaylist(saved.id)
            return completed(true, "Starting downloads for ${saved.name}.")
        }
        val remote = findRemotePlaylist(name)
            ?: return completed(false, "I couldn’t find a playlist called $name.")
        viewModel.applySearchedPlaylistBulkAction(remote, SearchedPlaylistBulkAction.DOWNLOAD_ALL)
        return completed(true, "Starting downloads for ${remote.title}.")
    }

    private suspend fun savePlaylist(name: String): VoiceExecution {
        val remote = findRemotePlaylist(name)
            ?: return completed(false, "I couldn’t find a YouTube Music playlist called $name.")
        viewModel.saveOfficialPlaylist(remote)
        return completed(true, "Saving ${remote.title} to your Library.")
    }

    private suspend fun findRemotePlaylist(name: String): SongItemClean? {
        val current = (viewModel.state.value.youtubeMusicPlaylists + viewModel.state.value.youtubeSearchPlaylistResults)
            .distinctBy { it.playlistId.ifBlank { it.id } }
            .maxByOrNull { PlaylistMatcher.score(name, it.title) }
            ?.takeIf { PlaylistMatcher.score(name, it.title) >= 0.48f }
        if (current != null) return current
        return withContext(Dispatchers.IO) {
            val community = runCatching { YtRepo.searchCommunityPlaylists(name) }.getOrDefault(emptyList())
            val publicYouTube = runCatching { YtRepo.searchYouTubePlaylists(name) }.getOrDefault(emptyList())
            (community + publicYouTube)
                .distinctBy { it.playlistId.ifBlank { it.id } }
                .maxByOrNull { PlaylistMatcher.score(name, it.title) }
                ?.takeIf { PlaylistMatcher.score(name, it.title) >= 0.48f }
        }
    }

    private fun completed(success: Boolean, message: String) = VoiceExecution(success, message)
}
