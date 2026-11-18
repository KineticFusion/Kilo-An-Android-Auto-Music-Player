@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.kilo.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.kilo.playback.crossfade.CrossfadeConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Native Crossfade Integration Helper.
 *
 * Instead of replacing the session's player, this integrates with the existing
 * PlayerHolder player by adding a standby player and using the native mixer
 * for crossfade transitions.
 *
 * The session continues to use the main PlayerHolder player. The native crossfade
 * engine runs in parallel with a standby player for the next track.
 */
@UnstableApi
class NativeCrossfadeIntegration(
    private val context: Context,
    private val mainPlayer: ExoPlayer,
    private val onCrossfadeComplete: (MediaItem) -> Unit
) {

    private val TAG = "NativeCrossfadeIntegration"

    private var standbyPlayer: ExoPlayer? = null
    private var standbyProcessor: DeckAudioProcessor? = null
    private var mainProcessor: DeckAudioProcessor? = null
    private var pendingNextMediaItem: MediaItem? = null
    private var crossfadeActive = false
    private var crossfadeDurationMs = 8000L
    private var automixEqEnabled = true

    private val initialized = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var monitorJob: Job? = null

    init {
        // Initialize native audio engine
        NativeAudioEngine.initialize(context)
    }

    /** Attach to the main player by adding the DeckAudioProcessor. */
    fun attach(): Boolean {
        if (initialized.getAndSet(true)) return true

        // Create processor for main player (Deck A)
        mainProcessor = DeckAudioProcessor(DeckAudioProcessor.DECK_A) { backpressure ->
            // Handle backpressure if needed
        }

        // Add processor to main player's audio sink
        // Note: This requires the main player to be recreated with the processor
        // For now, we'll use a separate standby player approach

        // Create standby player (Deck B) for next track
        createStandbyPlayer()

        Log.d(TAG, "NativeCrossfadeIntegration attached")
        return true
    }

    private fun createStandbyPlayer() {
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.CONTENT_TYPE_MUSIC)
            .build()

        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                standbyProcessor = DeckAudioProcessor(DeckAudioProcessor.DECK_B) { backpressure ->
                    // Handle backpressure
                }
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder()
                    .setAudioProcessors(arrayOf(standbyProcessor!!))
                    .setEnableFloatOutput(true)
                    .build()
            }
        }

        standbyPlayer = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            )
            .setAudioAttributes(audioAttributes, false) // Standby doesn't need audio focus
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NONE)
            .build()

        standbyPlayer?.volume = 0f
        standbyPlayer?.setHandleAudioBecomingNoisy(false)

        Log.d(TAG, "Standby player created for Deck B")
    }

    /** Prepare the next track for crossfade. */
    fun prepareNext(mediaItem: MediaItem, durationMs: Long = 8000L) {
        pendingNextMediaItem = mediaItem
        crossfadeDurationMs = durationMs

        standbyPlayer?.seekTo(0)
        standbyPlayer?.setPlayWhenReady(false)
        standbyPlayer?.setMediaItem(mediaItem)
        standbyPlayer?.prepare()

        // Sync playback parameters
        mainPlayer?.let { main ->
            standbyPlayer?.playbackParameters = main.playbackParameters
            standbyPlayer?.skipSilenceEnabled = main.skipSilenceEnabled
        }

        Log.d(TAG, "Prepared next track: ${mediaItem.mediaId}")
    }

    /** Start crossfade to the prepared next track. */
    fun startCrossfade() {
        if (crossfadeActive || pendingNextMediaItem == null || standbyPlayer == null) return

        val mainReady = mainPlayer?.playbackState == Player.STATE_READY
        val standbyReady = standbyPlayer?.playbackState == Player.STATE_READY

        if (!mainReady || !standbyReady) {
            Log.w(TAG, "Crossfade aborted: mainReady=$mainReady, standbyReady=$standbyReady")
            return
        }

        // Start standby player (at volume 0 - native mixer handles volume)
        standbyPlayer?.seekTo(0)
        standbyPlayer?.playWhenReady = true

        // Start native crossfade after a brief delay to allow standby to buffer audio
        // This prevents "fades out but doesn't fade in" when standby's buffer is empty
        scope.launch {
            // Wait for standby to start playing and fill some buffer
            var waitCount = 0
            while (waitCount < 10) { // Max ~100ms wait
                val fillRatioB = NativeAudioEngine.getFillRatio(NativeAudioEngine.DECK_B)
                if (fillRatioB > 0.1f) { // At least 10% buffered
                    break
                }
                kotlinx.coroutines.delay(10)
                waitCount++
            }

            // Check if crossfade was cancelled while waiting
            if (!crossfadeActive && pendingNextMediaItem == null) return@launch

            // Start native crossfade
            NativeAudioEngine.startCrossfade(crossfadeDurationMs, automixEqEnabled)

            crossfadeActive = true

            Log.i(TAG, "Crossfade started: ${mainPlayer?.currentMediaItem?.mediaId} -> ${pendingNextMediaItem?.mediaId}")
        }
    }

    /** Monitor crossfade progress and complete when done. */
    fun tick(): Boolean {
        if (!crossfadeActive) return false

        val isCrossfading = NativeAudioEngine.isCrossfading()
        if (!isCrossfading) {
            // Crossfade completed in native engine
            completeCrossfade()
            return true
        }
        return false
    }

    private fun completeCrossfade() {
        crossfadeActive = false

        val finishedItem = pendingNextMediaItem
        pendingNextMediaItem = null

        // Swap the main player's media item to the next track
        finishedItem?.let { item ->
            mainPlayer?.setMediaItem(item)
            mainPlayer?.seekTo(0)
            mainPlayer?.prepare()
            mainPlayer?.playWhenReady = true
            onCrossfadeComplete(item)
        }

        // Reset standby player
        standbyPlayer?.pause()
        standbyPlayer?.clearMediaItems()

        Log.i(TAG, "Crossfade complete, main player now playing: ${finishedItem?.mediaId}")
    }

    /** Cancel any pending crossfade. */
    fun cancelCrossfade(reason: String) {
        if (!crossfadeActive && pendingNextMediaItem == null) return
        crossfadeActive = false
        NativeAudioEngine.stopDeck(NativeAudioEngine.DECK_B)
        pendingNextMediaItem = null
        standbyPlayer?.pause()
        standbyPlayer?.clearMediaItems()
        Log.d(TAG, "Crossfade cancelled: $reason")
    }

    /** Set crossfade configuration. */
    fun setCrossfadeConfig(enabled: Boolean, durationMs: Long, automixEq: Boolean) {
        crossfadeDurationMs = durationMs
        automixEqEnabled = automixEq
    }

    /** Release resources. */
    fun release() {
        monitorJob?.cancel()
        monitorJob = null

        standbyPlayer?.release()
        standbyPlayer = null
        standbyProcessor = null
        mainProcessor = null

        NativeAudioEngine.release()
        initialized.set(false)

        Log.i(TAG, "NativeCrossfadeIntegration released")
    }

    companion object {
        /** Check if native crossfade should be used based on settings. */
        fun shouldUseNativeCrossfade(context: Context): Boolean {
            return CrossfadeConfig.read(context).enabled
        }
    }
}