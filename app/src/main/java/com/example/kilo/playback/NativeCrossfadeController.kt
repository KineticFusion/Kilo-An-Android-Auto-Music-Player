@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.kilo.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.example.kilo.PlayerHolder
import com.example.kilo.streaming.HybridEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Native Crossfade Controller - Replaces DualDeckEngine with C++ Native Mixer
 *
 * Architecture:
 * - Two ExoPlayer instances (Deck A, Deck B) configured with DeckAudioProcessor
 * - AudioProcessor feeds decoded PCM directly to native C++ ring buffers
 * - Native mixer (Oboe) outputs single mixed stream to hardware
 * - Sample-accurate equal-power crossfade in C++
 * - Automix EQ sweep (HPF 20Hz->350Hz) on outgoing deck
 * - Smart transition detection via AutomixTransitionAnalyzer
 */
class NativeCrossfadeController(
    private val context: Context,
    private val onTrackTransition: (String, String) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "NativeCrossfadeCtrl"
    }

    // Deck A = currently playing, Deck B = next track (standby)
    private var deckAPlayer: ExoPlayer? = null
    private var deckBPlayer: ExoPlayer? = null

    private var deckAProcessor: DeckAudioProcessor? = null
    private var deckBProcessor: DeckAudioProcessor? = null

    private var currentMediaId: String? = null
    private var nextMediaId: String? = null
    private var currentMediaItem: MediaItem? = null
    private var nextMediaItem: MediaItem? = null

    // Crossfade state
    private var crossfadeActive = false
    private var crossfadeStartMs = 0L
    private var crossfadeDurationMs = 8000L // Default 8s
    private var baseVolume = 1.0f

    // Automix state
    private var automixEnabled = true
    private var automixEqEnabled = true

    // Track analysis buffers for transition detection
    private var deckAAnalysisBuffer: FloatArray? = null
    private var deckBAnalysisBuffer: FloatArray? = null
    private val analysisBufferSize = 48000 * 30 // 30 seconds at 48kHz stereo
    private var deckAAnalysisFrames = 0
    private var deckBAnalysisFrames = 0

    // Lifecycle
    private val initialized = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Crossfade config
    private var minCrossfadeMs = 4000L
    private var maxCrossfadeMs = 12000L
    private var defaultCrossfadeMs = 8000L

    /**
     * Initialize the controller and native mixer.
     * Must be called before prepare().
     */
    fun initialize(): Boolean {
        if (initialized.getAndSet(true)) {
            Log.d(TAG, "Already initialized")
            return true
        }

        // Initialize native audio engine
        val success = NativeAudioEngine.initialize(context)
        if (!success) {
            initialized.set(false)
            Log.e(TAG, "Failed to initialize native audio engine")
            return false
        }

        // Create Deck A (primary) player with AudioProcessor
        deckAProcessor = DeckAudioProcessor(DeckAudioProcessor.DECK_A) { backpressure ->
            // Handle backpressure if needed
        }

        deckAPlayer = createPlayerWithProcessor(deckAProcessor!!)

        // Create Deck B (standby) player with AudioProcessor
        deckBProcessor = DeckAudioProcessor(DeckAudioProcessor.DECK_B) { backpressure ->
            // Handle backpressure
        }

        deckBPlayer = createPlayerWithProcessor(deckBProcessor!!)

        // Deck B starts muted (will be mixed by native engine)
        deckBPlayer?.volume = 0f
        deckBPlayer?.setHandleAudioBecomingNoisy(false)

        // Set up listeners
        setupDeckAListener()
        setupDeckBListener()

        Log.i(TAG, "NativeCrossfadeController initialized with native mixer")
        return true
    }

    private fun createPlayerWithProcessor(processor: DeckAudioProcessor): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .build()

        // Custom RenderersFactory that provides DefaultAudioSink with our processor
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder()
                    .setAudioProcessors(arrayOf(processor))
                    .setEnableFloatOutput(true)
                    .build()
            }
        }

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(mapOf("User-Agent" to "KILO/3.4.5"))
                    )
            )
            .setAudioAttributes(audioAttributes, true)
            .build()
    }

    private fun setupDeckAListener() {
        deckAPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    // Track ready - can start preparing next
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "Deck A error: ${error.message}", error)
                // Trigger recovery
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // Auto transition - crossfade will be triggered by outro detection
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing.set(isPlaying)
                if (!isPlaying) {
                    deckBPlayer?.pause()
                } else {
                    val nextItem = nextMediaItem
                    if (deckBProcessor?.isActive == true && nextItem != null) {
                        deckBPlayer?.play()
                    }
                }
            }
        })
    }

    private fun setupDeckBListener() {
        deckBPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val nextItem = nextMediaItem
                if (playbackState == Player.STATE_READY && nextItem != null) {
                    Log.d(TAG, "Deck B ready: ${nextItem.mediaId}")
                    // Analyze incoming track for intro detection
                    analyzeIncomingTrack()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "Deck B error: ${error.message}", error)
                nextMediaId = null
                nextMediaItem = null
            }
        })
    }

    /**
     * Prepare the next track for crossfade.
     * Call this when you know the next track (e.g., from queue).
     */
    fun prepareNext(mediaItem: MediaItem, crossfadeDurationMs: Long = defaultCrossfadeMs) {
        if (deckBPlayer == null || deckBProcessor == null) {
            Log.w(TAG, "Controller not initialized")
            return
        }

        nextMediaItem = mediaItem
        nextMediaId = mediaItem.mediaId
        this.crossfadeDurationMs = crossfadeDurationMs.coerceIn(minCrossfadeMs, maxCrossfadeMs)

        // Reset analysis buffer for incoming track
        deckBAnalysisFrames = 0

        // Prepare deck B (starts at volume 0, will be mixed by native)
        deckBPlayer?.seekTo(0)
        deckBPlayer?.setPlayWhenReady(false) // Will start when crossfade begins
        deckBPlayer?.setMediaItem(mediaItem)
        deckBPlayer?.prepare()

        // Sync playback parameters
        deckAPlayer?.let { deckA ->
            deckBPlayer?.playbackParameters = deckA.playbackParameters
            deckBPlayer?.skipSilenceEnabled = deckA.skipSilenceEnabled
        }

        Log.d(TAG, "Prepared next track: ${mediaItem.mediaId}, crossfade=${crossfadeDurationMs}ms")
    }

    /**
     * Start playback of current track.
     */
    fun playCurrent(mediaItem: MediaItem) {
        currentMediaItem = mediaItem
        currentMediaId = mediaItem.mediaId

        deckAPlayer?.seekTo(0)
        deckAPlayer?.setMediaItem(mediaItem)
        deckAPlayer?.prepare()
        deckAPlayer?.playWhenReady = true

        // Reset analysis buffer
        deckAAnalysisFrames = 0

        Log.d(TAG, "Playing current track: ${mediaItem.mediaId}")
    }

    /**
     * Start the crossfade transition to the prepared next track.
     * This is called automatically when outro is detected, or can be called manually.
     */
    fun startCrossfade() {
        if (crossfadeActive || nextMediaItem == null || deckBPlayer == null) return

        val deckAReady = deckAPlayer?.playbackState == Player.STATE_READY
        val deckBReady = deckBPlayer?.playbackState == Player.STATE_READY

        if (!deckAReady || !deckBReady) {
            Log.w(TAG, "Crossfade aborted: deckAReady=$deckAReady, deckBReady=$deckBReady")
            return
        }

        // Ensure deck B is at start and playing (at volume 0 - native mixer handles volume)
        deckBPlayer?.seekTo(0)
        deckBPlayer?.playWhenReady = true

        // Capture base volume
        baseVolume = deckAPlayer?.volume?.coerceIn(0f, 1f) ?: 1f

        // Start native crossfade
        NativeAudioEngine.startCrossfade(crossfadeDurationMs, automixEqEnabled)

        crossfadeActive = true
        crossfadeStartMs = System.currentTimeMillis()

        Log.i(TAG, "Crossfade started: ${currentMediaId} -> ${nextMediaId}, duration=${crossfadeDurationMs}ms, automixEQ=$automixEqEnabled")
    }

    /**
     * Complete the crossfade - swap decks.
     * Called when crossfade progress reaches 1.0
     */
    private fun completeCrossfade() {
        if (!crossfadeActive) return

        crossfadeActive = false

        // Swap decks: B becomes A, A becomes B
        val oldDeckA = deckAPlayer
        val oldDeckB = deckBPlayer
        val oldDeckAProcessor = deckAProcessor
        val oldDeckBProcessor = deckBProcessor

        val finishedMediaId = nextMediaId
        val finishedMediaItem = nextMediaItem

        deckAPlayer = oldDeckB
        deckBPlayer = oldDeckA
        deckAProcessor = oldDeckBProcessor
        deckBProcessor = oldDeckAProcessor

        currentMediaId = finishedMediaId
        currentMediaItem = finishedMediaItem
        nextMediaId = null
        nextMediaItem = null

        // Reset new standby deck
        deckBPlayer?.pause()
        deckBPlayer?.volume = 0f
        deckBPlayer?.clearMediaItems()

        // Notify transition complete
        onTrackTransition(currentMediaId ?: "", finishedMediaId ?: "")

        Log.i(TAG, "Crossfade complete, now playing: $currentMediaId")
    }

    /**
     * Cancel any pending crossfade and reset to single deck playback.
     */
    fun cancelCrossfade(reason: String) {
        if (!crossfadeActive && nextMediaItem == null) return
        crossfadeActive = false
        NativeAudioEngine.stopDeck(NativeAudioEngine.DECK_B)
        nextMediaId = null
        nextMediaItem = null
        deckBPlayer?.pause()
        deckBPlayer?.clearMediaItems()
        Log.d(TAG, "Crossfade cancelled: $reason")
    }

    /**
     * Analyze incoming track (Deck B) for intro detection.
     * This runs on the audio thread via the processor's backpressure callback.
     */
    private fun analyzeIncomingTrack() {
        scope.launch {
            // We'll analyze the track when enough PCM data is available
            // For now, use default crossfade timing
        }
    }

    /**
     * Monitor crossfade progress and analyze audio for smart transitions.
     * This is called periodically (e.g., from a handler or coroutine).
     */
    fun tick() {
        if (!initialized.get()) return

        // Monitor crossfade progress
        if (crossfadeActive) {
            val progress = NativeAudioEngine.getFillRatio(NativeAudioEngine.DECK_A) // Not exactly progress, but we can check
            val isCrossfading = NativeAudioEngine.isCrossfading()

            if (!isCrossfading) {
                // Crossfade completed in native engine
                completeCrossfade()
            }
        }

        // Check for outro on current track (Deck A) to trigger auto-crossfade
        if (!crossfadeActive && nextMediaItem != null && automixEnabled) {
            checkOutroTrigger()
        }

        // Backpressure monitoring
        val fillRatioA = NativeAudioEngine.getFillRatio(NativeAudioEngine.DECK_A)
        val fillRatioB = NativeAudioEngine.getFillRatio(NativeAudioEngine.DECK_B)

        if (fillRatioA > 0.9f || fillRatioB > 0.9f) {
            Log.w(TAG, "High buffer fill: A=${fillRatioA}, B=${fillRatioB}")
        }
    }

    /**
     * Check if outgoing track (Deck A) has reached outro threshold.
     * Uses RMS energy analysis on recently played audio.
     */
    private fun checkOutroTrigger() {
        // Get current energy from Deck A analysis buffer
        deckAAnalysisBuffer?.let { buffer ->
            val energyDb = AutomixTransitionAnalyzer.getCurrentEnergyDb(buffer, 4800) // ~100ms
            if (energyDb < AutomixTransitionAnalyzer.OUTRO_THRESHOLD_DB) {
                Log.d(TAG, "Outro detected on $currentMediaId (${energyDb}dB), triggering crossfade")
                startCrossfade()
            }
        }
    }

    /**
     * Feed PCM data to analysis buffers for transition detection.
     * This would be called from the DeckAudioProcessor after pushing to native mixer.
     * For now, we'll use a simplified approach.
     */
    fun feedAnalysisData(deckId: Int, pcmData: FloatArray, frames: Int) {
        if (deckId == DeckAudioProcessor.DECK_A) {
            deckAAnalysisBuffer?.let { buffer ->
                val samples = frames * 2
                val copyCount = minOf(samples, buffer.size - deckAAnalysisFrames * 2)
                if (copyCount > 0) {
                    System.arraycopy(pcmData, 0, buffer, deckAAnalysisFrames * 2, copyCount)
                    deckAAnalysisFrames += copyCount / 2
                    // Circular buffer
                    if (deckAAnalysisFrames >= buffer.size / 2) {
                        deckAAnalysisFrames = 0
                    }
                }
            }
        } else {
            deckBAnalysisBuffer?.let { buffer ->
                val samples = frames * 2
                val copyCount = minOf(samples, buffer.size - deckBAnalysisFrames * 2)
                if (copyCount > 0) {
                    System.arraycopy(pcmData, 0, buffer, deckBAnalysisFrames * 2, copyCount)
                    deckBAnalysisFrames += copyCount / 2
                    if (deckBAnalysisFrames >= buffer.size / 2) {
                        deckBAnalysisFrames = 0
                    }
                }
            }
        }
    }

    /**
     * Set crossfade configuration.
     */
    fun setCrossfadeConfig(enabled: Boolean, durationMs: Long, minMs: Long, maxMs: Long) {
        automixEnabled = enabled
        defaultCrossfadeMs = durationMs
        minCrossfadeMs = minMs
        maxCrossfadeMs = maxMs
    }

    /**
     * Enable/disable Automix EQ sweep.
     */
    fun setAutomixEqEnabled(enabled: Boolean) {
        automixEqEnabled = enabled
    }

    /**
     * Get current playback position.
     */
    fun getCurrentPosition(): Long = deckAPlayer?.currentPosition ?: 0L

    /**
     * Get current track duration.
     */
    fun getDuration(): Long = deckAPlayer?.duration ?: 0L

    /**
     * Seek to position.
     */
    fun seekTo(positionMs: Long) {
        deckAPlayer?.seekTo(positionMs)
    }

    /**
     * Set playback speed.
     */
    fun setPlaybackSpeed(speed: Float) {
        deckAPlayer?.let { player ->
            val params = player.playbackParameters
            player.playbackParameters = params.withSpeed(speed)
        }
        deckBPlayer?.let { player ->
            val params = player.playbackParameters
            player.playbackParameters = params.withSpeed(speed)
        }
    }

    /**
     * Pause playback.
     */
    fun pause() {
        deckAPlayer?.pause()
        deckBPlayer?.pause()
    }

    /**
     * Resume playback.
     */
    fun resume() {
        deckAPlayer?.playWhenReady = true
    }

    /**
     * Stop and release all resources.
     */
    fun release() {
        monitorJob?.cancel()
        monitorJob = null

        deckAPlayer?.release()
        deckBPlayer?.release()
        deckAPlayer = null
        deckBPlayer = null
        deckAProcessor = null
        deckBProcessor = null

        NativeAudioEngine.release()
        initialized.set(false)

        Log.i(TAG, "NativeCrossfadeController released")
    }

    /**
     * Get current crossfade progress [0.0, 1.0].
     */
    fun getCrossfadeProgress(): Float {
        return if (crossfadeActive) {
            val elapsed = System.currentTimeMillis() - crossfadeStartMs
            (elapsed.toFloat() / crossfadeDurationMs).coerceIn(0f, 1f)
        } else 0f
    }

    /**
     * Check if currently crossfading.
     */
    fun isCrossfading(): Boolean = crossfadeActive

    /**
     * Get current track metadata.
     */
    fun getCurrentMetadata(): MediaMetadata? = deckAPlayer?.currentMediaItem?.mediaMetadata

    /**
     * Get the currently active ExoPlayer (Deck A - the one currently playing).
     * This is used by NativeCrossfadePlayer to delegate playback state.
     */
    fun getActivePlayer(): ExoPlayer? = deckAPlayer

    /**
     * Get the standby ExoPlayer (Deck B - the one preparing next track).
     */
    fun getStandbyPlayer(): ExoPlayer? = deckBPlayer

    /**
     * Check if the controller is initialized.
     */
    fun isInitialized(): Boolean = initialized.get()

    /**
     * Get current media ID.
     */
    fun getCurrentMediaId(): String? = currentMediaId

    /**
     * Get next media ID.
     */
    fun getNextMediaId(): String? = nextMediaId
}