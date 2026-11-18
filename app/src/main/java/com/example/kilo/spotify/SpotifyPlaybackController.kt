package com.example.kilo.spotify

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Spotify Playback Controller
 * Bridges WebView player state to Kilo's ExoPlayer for notifications, Android Auto, and system integration
 * Handles free tier limitations (ads, no seek during ads)
 */
class SpotifyPlaybackController(
    private val context: Context,
    private val bridgeController: SpotifyBridgeController
) {
    private val TAG = "KiloSpotifyPlayback"

    // Current playback state from WebView
    internal val _webViewState = MutableStateFlow<BridgeSpotifyPlaybackState?>(null)
    val webViewState: StateFlow<BridgeSpotifyPlaybackState?> = _webViewState

    // Synced state for ExoPlayer/Android Auto
    private val _syncedState = MutableStateFlow<SyncedPlaybackState?>(null)
    val syncedState: StateFlow<SyncedPlaybackState?> = _syncedState

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    // Callbacks for when state changes
    private var onPlaybackStateChange: ((SyncedPlaybackState) -> Unit)? = null
    private var onPlayWhenReadyChange: ((Boolean) -> Unit)? = null

    // MediaSession integration for notifications and Android Auto
    private var mediaController: MediaController? = null
    private var isSpotifyActive = false

    init {
        observeWebViewState()
    }

    private fun observeWebViewState() {
        scope.launch {
            _webViewState.collect { state ->
                if (state != null) {
                    val synced = convertToSyncedState(state)
                    _syncedState.value = synced
                    onPlaybackStateChange?.invoke(synced)
                    onPlayWhenReadyChange?.invoke(!state.isPaused && !state.isBuffering)

                    // Update MediaSession if Spotify is active
                    if (isSpotifyActive && mediaController != null) {
                        updateMediaSessionMetadata(synced)
                    }
                } else if (isSpotifyActive) {
                    // Spotify playback stopped, clear MediaSession metadata
                    clearMediaSessionMetadata()
                }
            }
        }
    }

    private fun convertToSyncedState(state: BridgeSpotifyPlaybackState): SyncedPlaybackState {
        val track = state.track
        val isAd = track?.kind == "ad"
        val canSeek = !isAd && !state.isBuffering
        val canSkip = !isAd

        return SyncedPlaybackState(
            trackId = track?.uri ?: "",
            trackName = track?.name ?: "",
            artistName = track?.artists?.map { it.name }?.joinToString(", ") ?: "",
            albumName = track?.album?.name ?: "",
            coverUrl = track?.image ?: "",
            durationMs = state.durationMs,
            positionMs = state.positionMs,
            isPlaying = !state.isPaused && !state.isBuffering,
            isBuffering = state.isBuffering,
            isAd = isAd,
            canSeek = canSeek,
            canSkipNext = canSkip,
            canSkipPrev = canSkip,
            shuffle = state.shuffle,
            repeatMode = state.repeat,
            volume = 1f, // Would need async call to get actual volume
            playbackId = state.playbackId,
            restrictions = state.restrictions?.toString() ?: ""
        )
    }

    fun setOnPlaybackStateChange(listener: (SyncedPlaybackState) -> Unit) {
        onPlaybackStateChange = listener
    }

    fun setOnPlayWhenReadyChange(listener: (Boolean) -> Unit) {
        onPlayWhenReadyChange = listener
    }

    // Playback controls - delegates to WebView bridge
    fun play(contextUri: String, trackUri: String?, trackUid: String?, index: Int?, positionMs: Long?) {
        bridgeController.play(contextUri, trackUri, trackUid, index, positionMs)
    }

    fun pause() {
        bridgeController.pause()
    }

    fun resume() {
        bridgeController.resume()
    }

    fun seek(positionMs: Long) {
        val state = _webViewState.value
        if (state != null && state.track?.kind != "ad") {
            bridgeController.seek(positionMs)
        } else {
            Log.w(TAG, "Seek blocked: ad playing or no state")
        }
    }

    fun next() {
        val state = _webViewState.value
        if (state == null || state.track?.kind != "ad") {
            bridgeController.next()
        } else {
            Log.w(TAG, "Next blocked: ad playing")
        }
    }

    fun previous() {
        val state = _webViewState.value
        if (state == null || state.track?.kind != "ad") {
            bridgeController.previous()
        } else {
            Log.w(TAG, "Previous blocked: ad playing")
        }
    }

    fun setShuffle(on: Boolean) {
        bridgeController.setShuffle(on)
    }

    fun setRepeat(mode: Int) {
        bridgeController.setRepeat(mode)
    }

    fun addToQueue(uris: List<String>) {
        bridgeController.addToQueue(uris)
    }

    fun removeFromQueue(items: List<QueueItem>) {
        bridgeController.removeFromQueue(items)
    }

    fun clearQueue() {
        bridgeController.clearQueue()
    }

    fun setVolume(level: Float) {
        bridgeController.setVolume(level)
    }

    fun getQueue(): BridgeSpotifyQueue? {
        return bridgeController.getQueue()
    }

    fun shutdown() {
        job.cancel()
    }

    // Custom command for Spotify metadata updates (must match VybeMediaService)
    private val SPOTIFY_METADATA_COMMAND = SessionCommand("vybe_spotify_metadata", Bundle.EMPTY)

    // MediaController integration methods (for notifications and Android Auto)
    fun setMediaController(controller: MediaController) {
        mediaController = controller
        Log.d(TAG, "MediaController set for Spotify playback")
    }

    fun setSpotifyActive(active: Boolean) {
        isSpotifyActive = active
        if (!active) {
            clearMediaSessionMetadata()
        }
        Log.d(TAG, "Spotify active set to: $active")
    }

    private fun updateMediaSessionMetadata(state: SyncedPlaybackState) {
        if (mediaController == null) return

        // Send custom command to MediaSession to update metadata and playback state
        val args = Bundle().apply {
            putString("title", state.trackName)
            putString("artist", state.artistName)
            putString("album", state.albumName)
            putString("coverUrl", state.coverUrl)
            putLong("durationMs", state.durationMs)
            putLong("positionMs", state.positionMs)
            putBoolean("isPlaying", state.isPlaying)
            putBoolean("isBuffering", state.isBuffering)
            putBoolean("canSeek", state.canSeek)
            putBoolean("canSkipNext", state.canSkipNext)
            putBoolean("canSkipPrev", state.canSkipPrev)
            putBoolean("shuffle", state.shuffle)
            putInt("repeatMode", state.repeatMode)
        }

        mediaController?.sendCustomCommand(SPOTIFY_METADATA_COMMAND, args)

        Log.d(TAG, "Sent Spotify metadata to MediaSession: ${state.trackName} - ${state.artistName}, playing: ${state.isPlaying}")
    }

    private fun clearMediaSessionMetadata() {
        if (mediaController == null) return

        // Send custom command with empty metadata to clear
        val args = Bundle().apply {
            putString("title", "KILO Music")
            putString("artist", "Ready to stream")
            putString("album", "")
            putString("coverUrl", "")
            putLong("durationMs", 0)
            putLong("positionMs", 0)
            putBoolean("isPlaying", false)
            putBoolean("isBuffering", false)
            putBoolean("canSeek", false)
            putBoolean("canSkipNext", false)
            putBoolean("canSkipPrev", false)
            putBoolean("shuffle", false)
            putInt("repeatMode", 0)
        }

        mediaController?.sendCustomCommand(SPOTIFY_METADATA_COMMAND, args)

        Log.d(TAG, "Cleared MediaSession metadata via custom command")
    }
}

/**
 * Synced playback state for Kilo's ExoPlayer, notifications, Android Auto
 */
data class SyncedPlaybackState(
    val trackId: String,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val coverUrl: String,
    val durationMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val isAd: Boolean,
    val canSeek: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrev: Boolean,
    val shuffle: Boolean,
    val repeatMode: Int,
    val volume: Float,
    val playbackId: String?,
    val restrictions: String
)

/**
 * Extension to integrate with Kilo's existing playback system
 */
fun SyncedPlaybackState.toMediaMetadata(): androidx.media3.common.MediaMetadata {
    return androidx.media3.common.MediaMetadata.Builder()
        .setTitle(trackName)
        .setArtist(artistName)
        .setAlbumTitle(albumName)
        .setArtworkUri(android.net.Uri.parse(coverUrl))
        .setDurationMs(durationMs)
        .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC)
        .setIsPlayable(true)
        .build()
}