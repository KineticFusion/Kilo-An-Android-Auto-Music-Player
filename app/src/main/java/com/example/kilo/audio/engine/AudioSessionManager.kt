package com.example.kilo.audio.engine

import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.kilo.PlayerHolder
import com.example.kilo.audio.diagnostics.AudioAnalyzer
import com.example.kilo.audio.diagnostics.AudioHealthMonitor
import com.example.kilo.audio.diagnostics.AudioMetrics
import com.example.kilo.audio.effects.BassController
import com.example.kilo.audio.effects.EqualizerController
import com.example.kilo.audio.effects.ExciterController
import com.example.kilo.audio.effects.LoudnessController
import com.example.kilo.audio.effects.DynamicsProcessingSession
import com.example.kilo.audio.effects.VirtualizerController
import com.example.kilo.audio.profiles.SoundProfileRepository
import com.example.kilo.audio.dsp.SpatialEnhancer
import com.example.kilo.audio.dsp.StereoWidener
import com.example.kilo.audioanalysis.TrackAnalysisEntity
import java.util.concurrent.Executor

/** Owns platform effects for KILO's existing Media3 audio session; never issues player commands. */
class AudioSessionManager(
    context: Context,
    private val player: Player,
    private val handler: Handler,
    private val backgroundExecutor: Executor,
    private val profiles: SoundProfileRepository,
    private val onState: (AudioEngineState) -> Unit
) {
    private val appContext = context.applicationContext
    private val outputDevices = OutputDeviceManager(appContext, handler) { route ->
        routeSnapshot = route
        refresh()
    }
    private val pipeline = AudioPipeline(profiles)
    private val health = AudioHealthMonitor()
    private val eq = EqualizerController()
    private val loudness = LoudnessController()
    private val fallbackBass = BassController()
    private val virtualizer = VirtualizerController()
    private val exciter = ExciterController()
    private val spatialEnhancer = SpatialEnhancer(appContext)
    private val stereoWidener = StereoWidener()
    private var dynamics: DynamicsProcessingSession? = null
    private var activeSessionId = 0
    private var routeSnapshot = AudioOutputRoute()
    private var activeAnalysis: TrackAnalysisEntity? = null
    private var activeTrackId = ""
    @Volatile private var observedSessionId = 0
    private var closed = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            observedSessionId = readAudioSessionId(observedSessionId)
            if (playbackState == Player.STATE_READY && player.playWhenReady) refresh()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val trackId = mediaItem?.mediaId.orEmpty()
            if (trackId.isBlank()) return
            observedSessionId = readAudioSessionId(observedSessionId)
            activeTrackId = trackId
            activeAnalysis = null
            refresh()
            if (profiles.selectedId() == SoundProfileRepository.ORIGINAL) return
            // Existing cached analysis is optional. Its database lookup runs off the player/UI thread.
            backgroundExecutor.execute {
                PlayerHolder.loadAnalysis(trackId, backgroundExecutor).whenComplete { result, error ->
                    if (error != null) Log.d(TAG, "cached track analysis unavailable trackId=$trackId", error)
                    if (result != null) handler.post {
                        if (!closed && activeTrackId == trackId) {
                            activeAnalysis = result
                            refreshOnWorker()
                        }
                    }
                }
            }
        }
    }

    fun start() {
        player.addListener(listener)
        observedSessionId = readAudioSessionId(0)
        outputDevices.start()
        refresh()
    }

    fun refresh() {
        if (!closed) handler.post(::refreshOnWorker)
    }

    fun close() {
        closed = true
        runCatching { player.removeListener(listener) }
        outputDevices.close()
        releaseEffects()
    }

    private fun refreshOnWorker() {
        if (closed) return
        val sessionId = observedSessionId
        if (sessionId <= 0) {
            val selected = profiles.forOutput(routeSnapshot)
            publish(AudioEngineState(
                output = routeSnapshot,
                selectedProfileId = selected.id,
                activeProfileTitle = selected.title,
                message = if (selected.id == SoundProfileRepository.ORIGINAL) {
                    "Original tone with a level boost; peak limiting applies during playback"
                } else {
                    "Start playback to apply this sound preset"
                }
            ))
            return
        }
        if (activeSessionId != sessionId) {
            releaseEffects()
            activeSessionId = sessionId
        }

        val limiterAvailable = Build.VERSION.SDK_INT >= 28
        val plan = pipeline.createPlan(routeSnapshot, activeAnalysis, limiterAvailable)
        if (plan.transparentMode) {
            releaseEffects()
            activeSessionId = sessionId
            health.onProfileState(false)
            val analyzer = AudioAnalyzer(false, spatialEnhancer.platformSpatializerAvailable, exciter.supported)
            val state = AudioEngineState(
                output = routeSnapshot,
                selectedProfileId = profiles.selectedId(),
                activeProfileTitle = plan.activeProfile.title,
                effectsActive = false,
                limiterActive = false,
                analysisApplied = false,
                analyzer = analyzer,
                message = plan.description
            )
            AudioMetrics.profileApplied(routeSnapshot.kind.label, plan.activeProfile.title, sessionId, false, "none (original)")
            publish(state)
            return
        }

        var dynamicsReady = dynamics != null
        if (Build.VERSION.SDK_INT >= 28 && dynamics == null) {
            runCatching { createDynamics(sessionId) }
                .onSuccess { dynamics = it; dynamicsReady = true }
                .onFailure { health.onEffectFailure("DynamicsProcessing", it) }
        }

        val effectivePlan = pipeline.createPlan(routeSnapshot, activeAnalysis, dynamicsReady)
        val activeEffects = mutableListOf<String>()
        if (dynamics != null) {
            runCatching {
                dynamics!!.apply(
                    eq.boundedGains(effectivePlan.eqGainsDb),
                    loudness.safeInputGain(effectivePlan.inputGainDb, effectivePlan.limiterEnabled)
                )
                activeEffects += "10-band EQ"
                if (effectivePlan.limiterEnabled) activeEffects += "limiter"
                if (effectivePlan.inputGainDb > 0f) activeEffects += "${"%.1f".format(effectivePlan.inputGainDb)}dB gain"
            }.onFailure { health.onEffectFailure("DynamicsProcessing.apply", it) }
        } else if (effectivePlan.eqGainsDb.take(3).any { it > 0f }) {
            if (fallbackBass.attach(sessionId, 240)) activeEffects += "bass (fallback)"
        }

        val stereoStrength = stereoWidener.virtualizerStrength(
            effectivePlan.activeProfile,
            effectivePlan.output.kind == AudioOutputKind.CAR_BLUETOOTH || effectivePlan.output.kind == AudioOutputKind.ANDROID_AUTO
        )
        if (stereoStrength > 0 && virtualizer.attach(sessionId, stereoStrength)) {
            activeEffects += "virtualizer"
        } else {
            virtualizer.release()
        }

        val limiterActive = dynamics != null && effectivePlan.limiterEnabled
        health.onProfileState(limiterActive)
        val state = AudioEngineState(
            output = routeSnapshot,
            selectedProfileId = profiles.selectedId(),
            activeProfileTitle = effectivePlan.activeProfile.title,
            effectsActive = activeEffects.isNotEmpty(),
            limiterActive = limiterActive,
            analysisApplied = effectivePlan.analysisUsed,
            analyzer = AudioAnalyzer(dynamics != null, spatialEnhancer.platformSpatializerAvailable, exciter.supported),
            message = when {
                dynamics != null && limiterActive && effectivePlan.inputGainDb > 0f -> "${effectivePlan.description}; limiter is active"
                dynamics != null -> "${effectivePlan.activeProfile.description}; session effects are active"
                else -> "This device does not expose the full profile; playback remains unchanged"
            }
        )
        AudioMetrics.profileApplied(routeSnapshot.kind.label, effectivePlan.activeProfile.title, sessionId, limiterActive, activeEffects.joinToString().ifBlank { "none" })
        publish(state)
    }

    private fun createDynamics(sessionId: Int): DynamicsProcessingSession = DynamicsProcessingSession(sessionId)

    private fun readAudioSessionId(fallback: Int): Int =
        runCatching { (player as? ExoPlayer)?.audioSessionId ?: fallback }.getOrDefault(fallback)

    private fun releaseEffects() {
        runCatching { dynamics?.release() }
        dynamics = null
        fallbackBass.release()
        virtualizer.release()
    }

    private fun publish(state: AudioEngineState) = onState(state.copy(message = state.message.ifBlank { health.snapshot() }))

    private companion object { const val TAG = "KiloAudioEngine" }
}
