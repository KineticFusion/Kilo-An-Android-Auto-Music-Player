package com.example.kilo.playback.crossfade

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.example.kilo.playback.NativeAudioEngine
import com.example.kilo.playback.NativeCrossfadeController
import com.example.kilo.playback.NativeCrossfadeIntegration
import com.example.kilo.PlayerHolder

/** Top-level lifecycle facade for the native C++ crossfade engine.
 * Integrates with the existing PlayerHolder player by adding a standby player
 * and using the native mixer for crossfade transitions. */
class CrossfadeOrchestrator(orchestratorContext: Context) {
    private val appContext = orchestratorContext.applicationContext
    private val controller = NativeCrossfadeController(appContext) { fromId, toId ->
        // Track transition callback - can be used for analytics
    }

    // Integration with main player - will be initialized when main player is available
    private var integration: NativeCrossfadeIntegration? = null

    init {
        controller.initialize()
    }

    /** Initialize the integration with the main player from PlayerHolder. */
    fun initializeWithMainPlayer(mainPlayer: ExoPlayer) {
        if (integration != null) return
        integration = NativeCrossfadeIntegration(
            context = appContext,
            mainPlayer = mainPlayer,
            onCrossfadeComplete = { nextItem ->
                // Notify that crossfade completed and main player now plays next track
            }
        ).also { it.attach() }
    }

    fun tick(): Long {
        controller.tick()
        integration?.tick()
        return 16L // ~60fps for smooth crossfade monitoring
    }

    fun cancel(reason: String) {
        controller.cancelCrossfade(reason)
        integration?.cancelCrossfade(reason)
    }

    fun onStop() {
        integration?.release()
        integration = null
        controller.release()
    }

    fun playCurrent(mediaItem: MediaItem) {
        controller.playCurrent(mediaItem)
    }

    fun prepareNext(mediaItem: MediaItem, crossfadeDurationMs: Long = 8000L) {
        controller.prepareNext(mediaItem, crossfadeDurationMs)
        integration?.prepareNext(mediaItem, crossfadeDurationMs)
    }

    fun startCrossfade() {
        controller.startCrossfade()
        integration?.startCrossfade()
    }

    fun isCrossfading(): Boolean = controller.isCrossfading()

    fun getCrossfadeProgress(): Float = controller.getCrossfadeProgress()

    /** Get the underlying controller for advanced operations. */
    fun getController(): NativeCrossfadeController = controller

    /** Get the active ExoPlayer for direct access if needed. */
    fun getActivePlayer(): ExoPlayer? = controller.getActivePlayer()
}