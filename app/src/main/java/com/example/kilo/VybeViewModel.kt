@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.kilo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.kilo.playback.stability.MediaSourceAudit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VybeState(
    val isSetupComplete: Boolean = false,
    val setupStep: Int = 0,
    val isFullScreenPlayerOpen: Boolean = false,
    val quickPicks: List<SongItem> = BITCHORD_CURATED_HITS,
    val playlist: List<SongItem> = BITCHORD_CURATED_HITS,
    val searchResults: List<SongItem> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isLoadingStream: Boolean = false,
    val progress: Float = 0f,
    val currentPosMs: Long = 0L,
    val currentDurationMs: Long = 215000L,
    val isFavorite: Boolean = false,
    val isSearching: Boolean = false,
    val currentScreen: AppScreen = AppScreen.HOME
) {
    val activeSong: SongItem
        get() = playlist.getOrNull(currentIndex)
            ?: quickPicks.firstOrNull()
            ?: BITCHORD_CURATED_HITS.first()
}

class VybeViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(VybeState())
    val state: StateFlow<VybeState> = _state.asStateFlow()

    private val player = ExoPlayer.Builder(application).build().apply {
        repeatMode = Player.REPEAT_MODE_OFF
        setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .build(),
            true
        )
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    nextTrack()
                }
            }
        })
    }

    init {
        loadHomeFeed()
        viewModelScope.launch {
            while (true) {
                if (player.isPlaying) {
                    val realDur = if (player.duration > 0) player.duration else _state.value.currentDurationMs
                    val pos = player.currentPosition
                    _state.value = _state.value.copy(
                        isPlaying = true,
                        currentPosMs = pos,
                        currentDurationMs = realDur,
                        progress = (pos.toFloat() / realDur.toFloat()).coerceIn(0f, 1f)
                    )
                }
                delay(250)
            }
        }
    }

    private fun loadHomeFeed() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSearching = true)
            val fetched = InnerTubeApiService.searchSongs("Top Global Hits 2026")
            _state.value = _state.value.copy(
                quickPicks = fetched,
                playlist = fetched,
                currentIndex = 0,
                isSearching = false
            )
            if (fetched.isNotEmpty()) loadTrack(0, autoPlay = false)
        }
    }

    fun completeSetupStep(nextStep: Int) {
        if (nextStep > 7) {
            _state.value = _state.value.copy(isSetupComplete = true, currentScreen = AppScreen.HOME)
        } else {
            _state.value = _state.value.copy(setupStep = nextStep)
        }
    }

    fun setScreen(screen: AppScreen) {
        _state.value = _state.value.copy(currentScreen = screen)
    }

    fun searchMusic(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSearching = true)
            val results = InnerTubeApiService.searchSongs(query)
            _state.value = _state.value.copy(
                searchResults = results,
                isSearching = false
            )
        }
    }

    fun playSongFromShelf(song: SongItem, shelfList: List<SongItem>) {
        val targetQueue = if (shelfList.isNotEmpty()) shelfList else listOf(song)
        val index = targetQueue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        _state.value = _state.value.copy(playlist = targetQueue, currentIndex = index)
        loadTrack(index, autoPlay = true)
    }

    fun loadTrack(index: Int, autoPlay: Boolean = true) {
        val list = _state.value.playlist.ifEmpty { _state.value.quickPicks }
        if (list.isEmpty()) return
        val idx = (index + list.size) % list.size
        val song = list[idx]

        _state.value = _state.value.copy(
            currentIndex = idx,
            progress = 0f,
            currentPosMs = 0L,
            currentDurationMs = song.durationMs,
            isLoadingStream = true
        )

        viewModelScope.launch {
            val fresh = InnerTubeApiService.fetchPlayerMetadata(song.videoId.ifEmpty { song.id }, 0)
            val playableUrl = fresh?.audioUrl?.ifEmpty { song.audioUrl } ?: song.audioUrl
            _state.value = _state.value.copy(isLoadingStream = false)

            if (playableUrl.isNotEmpty()) {
                val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .setConnectTimeoutMs(2500)
                    .setReadTimeoutMs(2500)
                    .setDefaultRequestProperties(mapOf(
                        "Origin" to "https://music.youtube.com",
                        "Referer" to "https://music.youtube.com/"
                    ))

                val mediaSourceFactory = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                val mediaItem = MediaItem.fromUri(playableUrl)

                withContext(Dispatchers.Main) {
                    MediaSourceAudit.beforeMutation("set_media_source:vybe_view_model", player, song.videoId.ifBlank { song.id })
                    player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
                    MediaSourceAudit.beforeMutation("prepare:vybe_view_model", player, song.videoId.ifBlank { song.id })
                    player.prepare()
                    if (autoPlay) {
                        player.play()
                        _state.value = _state.value.copy(isPlaying = true)
                    }
                }
            }
        }
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            _state.value = _state.value.copy(isPlaying = false)
        } else {
            player.play()
            _state.value = _state.value.copy(isPlaying = true)
        }
    }

    fun nextTrack() = loadTrack(_state.value.currentIndex + 1, autoPlay = true)
    fun prevTrack() = loadTrack(_state.value.currentIndex - 1, autoPlay = true)

    fun seekTo(frac: Float) {
        val dur = if (player.duration > 0) player.duration else _state.value.currentDurationMs
        val target = (dur * frac).toLong()
        player.seekTo(target)
        _state.value = _state.value.copy(progress = frac, currentPosMs = target)
    }

    fun toggleFavorite() {
        _state.value = _state.value.copy(isFavorite = !_state.value.isFavorite)
    }

    fun toggleDownloadSong(songId: String) {
        val updatedList = _state.value.playlist.map {
            if (it.id == songId) it.copy(isDownloaded = !it.isDownloaded) else it
        }
        _state.value = _state.value.copy(playlist = updatedList)
    }

    fun setFullScreenPlayer(open: Boolean) {
        _state.value = _state.value.copy(isFullScreenPlayerOpen = open)
    }

    override fun onCleared() {
        MediaSourceAudit.beforeMutation("release:vybe_view_model", player)
        player.release()
    }
}
