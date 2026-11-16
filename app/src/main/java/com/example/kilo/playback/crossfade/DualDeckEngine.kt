package com.example.kilo.playback.crossfade

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.kilo.PlayerHolder
import com.example.kilo.playback.preparation.NextTrackPreparationManager
import com.example.kilo.playback.preparation.PreloadCoordinator
import com.example.kilo.playback.preparation.StreamPreparationMetrics
import com.example.kilo.lyrics.LyricsManager
import com.example.kilo.toSong

/**
 * True crossfade implementation inspired by BitChord.
 *
 * Key differences from previous implementation:
 * - Standby deck keeps playing at volume 0 during priming (no pause/seek cycle)
 * - Crossfade starts at exact right moment based on remaining time
 * - Simplified handoff - no waiting for primary deck transition
 * - Better buffer management for reliable crossfades
 */
class DualDeckEngine(
    context: Context,
    private val deckAPlayer: ExoPlayer,
    private val deckBPlayer: ExoPlayer
) {
    private val app = context.applicationContext
    private val deckA = DeckAController(deckAPlayer)
    private val deckB = DeckBController(deckBPlayer)
    private val mixer = AudioMixer(deckAPlayer, deckBPlayer)
    private val recovery = CrossfadeRecovery(deckAPlayer, deckBPlayer, mixer)
    private val transitionManager = TransitionManager(app)
    private val preloadCoordinator = PreloadCoordinator()
    private val preparationManager = NextTrackPreparationManager(deckBPlayer, preloadCoordinator.cache)

    private var preparedId: String? = null
    private var fadeStarted = false
    private var fadeCompleted = false
    private var fadeStartedAtMs = 0L
    private var fadeDurationMs = 0L
    private var baseGain = 1f
    private var lastPrimaryId = ""
    private var preparationStartedAtMs = 0L

    // Track if we're in a crossfade transition
    private var crossfadeActive = false

    init {
        deckBPlayer.volume = 0f
        deckBPlayer.setHandleAudioBecomingNoisy(false) // Standby should not handle audio focus

        deckBPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && deckB.mediaId == preparedId) {
                    deckB.markReady()
                    preparationManager.snapshot()
                    StreamPreparationMetrics.prepared(deckB.mediaId, SystemClock.elapsedRealtime() - preparationStartedAtMs, deckB.bufferedMs)
                    Log.d("KiloCrossfade", "standby_ready id=${deckB.mediaId} bufferedMs=${deckB.bufferedMs}")
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val failedId = preparedId ?: deckB.mediaId
                recovery.standbyFailed(failedId, error, baseGain)
                CrossfadeMetrics.failed(failedId, "standby_prepare")
                clearPreparation()
            }
        })

        deckAPlayer.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) {
                    deckBPlayer.pause()
                } else if (priming && deckB.ready) {
                    // Resume standby priming when primary resumes
                    deckBPlayer.play()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                handlePrimaryTransition(mediaItem, reason)
            }
        })
    }

    /** Called from KILO's existing player sampler; no extra always-running thread is created. */
    fun tick(): Long {
        if (handoffId != null) return tickHandoff()

        val currentItem = deckAPlayer.currentMediaItem ?: return cancel("no_current_item").let { IDLE_TICK_MS }
        val currentId = currentItem.mediaId

        if (currentId != lastPrimaryId) {
            if (lastPrimaryId.isNotBlank() && currentId != preparedId) cancel("primary_item_changed")
            lastPrimaryId = currentId
        }

        if (!deckAPlayer.playWhenReady) {
            deckBPlayer.pause()
            return IDLE_TICK_MS
        }

        // If crossfade is active, handle the fade
        if (fadeStarted) {
            return handleActiveCrossfade()
        }

        val config = CrossfadeConfig.read(app)
        if (!config.enabled || config.durationMs <= 0L || deckAPlayer.repeatMode == Player.REPEAT_MODE_ONE) {
            if (preparedId != null) cancel("disabled_or_repeat_one")
            return IDLE_TICK_MS
        }

        val next = nextQueueItem() ?: return cancel("no_next_queue_item").let { IDLE_TICK_MS }
        if (next.mediaId == currentId || next.mediaId.startsWith("radio:")) return cancel("unsupported_item").let { IDLE_TICK_MS }

        val plan = transitionManager.plan(currentItem, next)
        if (plan.mode != TransitionMode.CROSSFADE || plan.durationMs <= 0L) {
            if (preparedId != null) cancel("policy_${plan.mode}")
            return IDLE_TICK_MS
        }

        val remaining = deckA.remainingMs

        // Prepare next track early
        if (TransitionScheduler.shouldPrepare(remaining, preparedId == next.mediaId)) {
            prepareNext(next, plan)
        } else if (preparedId != null && preparedId != next.mediaId) {
            cancel("queue_next_changed")
        }

        // Start crossfade at the right moment
        if (preparedId == next.mediaId && deckB.ready && !fadeStarted &&
            TransitionScheduler.shouldStart(remaining, plan.durationMs, deckB.ready, deckB.bufferedMs)
        ) {
            startCrossfade(currentItem, next, plan.durationMs)
        }

        return IDLE_TICK_MS
    }

    private fun handleActiveCrossfade(): Long {
        // Check if standby is buffering during fade
        if (deckBPlayer.playbackState == Player.STATE_BUFFERING && deckB.bufferedMs < MINIMUM_SAFE_FADE_BUFFER_MS) {
            Log.w("KiloCrossfade", "standby_rebuffer_during_fade id=${deckB.mediaId} bufferedMs=${deckB.bufferedMs}; restoring active deck")
            cancel("standby_rebuffer_during_fade")
            return IDLE_TICK_MS
        }

        val progress = ((SystemClock.elapsedRealtime() - fadeStartedAtMs).toFloat() /
            fadeDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)

        val outgoingRms = com.example.kilo.audioanalysis.LoudnessAnalyzer.linearRms(
            PlayerHolder.cachedTrackAnalysis(deckA.mediaId)
        )
        val incomingRms = com.example.kilo.audioanalysis.LoudnessAnalyzer.linearRms(
            PlayerHolder.cachedTrackAnalysis(deckB.mediaId)
        )
        mixer.apply(progress, baseGain, outgoingRms, incomingRms)

        if (progress >= 1f && !fadeCompleted) {
            fadeCompleted = true
            completeCrossfade()
        }

        return FADE_TICK_MS
    }

    private fun nextQueueItem(): MediaItem? {
        if (deckAPlayer.mediaItemCount < 2) return null
        val nextIndex = deckAPlayer.currentMediaItemIndex + 1
        return when {
            nextIndex < deckAPlayer.mediaItemCount -> deckAPlayer.getMediaItemAt(nextIndex)
            deckAPlayer.repeatMode == Player.REPEAT_MODE_ALL -> deckAPlayer.getMediaItemAt(0)
            else -> null
        }
    }

    private fun prepareNext(next: MediaItem, plan: TransitionPlan) {
        if (preparedId == next.mediaId) return
        cancel("prepare_replacement")
        if (!preloadCoordinator.decoderPool.acquire(next.mediaId)) return
        preparedId = next.mediaId
        preparationStartedAtMs = SystemClock.elapsedRealtime()
        PlayerHolder.setStandbyAnalysisMediaId(next.mediaId)
        fadeStarted = false
        fadeCompleted = false
        fadeDurationMs = plan.durationMs
        deckB.preparing(next)
        deckBPlayer.setPlaybackSpeed(deckAPlayer.playbackParameters.speed)
        deckBPlayer.setSkipSilenceEnabled(deckAPlayer.skipSilenceEnabled)
        LyricsManager.prefetch(next.toSong())
        CrossfadeMetrics.plan(deckA.mediaId, plan.mode, plan.durationMs, plan.reason)
        Log.i("KiloCrossfade", "standby_prepare id=${next.mediaId} title=${next.mediaMetadata.title}")
    }

    private fun startCrossfade(current: MediaItem, next: MediaItem, durationMs: Long) {
        if (!deckB.ready || !deckAPlayer.isPlaying) {
            Log.w("KiloCrossfade", "crossfade_aborted deckB_ready=${deckB.ready} deckA_playing=${deckAPlayer.isPlaying}")
            return
        }

        baseGain = deckAPlayer.volume.coerceIn(0f, 1f).takeIf { it > 0f } ?: 1f
        // Clamp fade duration to remaining time to avoid extending past track end
        fadeDurationMs = durationMs.coerceAtMost(deckA.remainingMs.coerceAtLeast(1L))
        fadeStartedAtMs = SystemClock.elapsedRealtime()
        fadeStarted = true
        fadeCompleted = false
        crossfadeActive = true

        // Ensure standby is at start position and playing at volume 0
        deckBPlayer.seekTo(0L)
        deckBPlayer.volume = 0f
        deckB.markFadingIn()
        deckBPlayer.play()

        CrossfadeMetrics.started(current.mediaId, next.mediaId, fadeDurationMs)
        Log.i("KiloCrossfade", "crossfade_start from=${current.mediaId} to=${next.mediaId} durationMs=$fadeDurationMs remainingMs=${deckA.remainingMs} curve=equal_power")
    }

    private fun completeCrossfade() {
        // Crossfade complete - swap decks
        deckAPlayer.volume = 0f
        deckBPlayer.volume = baseGain.coerceIn(0f, 1f)
        deckB.markActive()
        deckAPlayer.pause() // Primary will be replaced by handoff
        CrossfadeMetrics.overlapProgress(deckA.mediaId, deckA.remainingMs, deckB.bufferedMs)
        Log.i("KiloCrossfade", "crossfade_complete to=${deckB.mediaId}")

        // Schedule handoff - the primary deck will transition to next item automatically
        handoffId = deckB.mediaId
    }

    private fun handlePrimaryTransition(item: MediaItem?, reason: Int) {
        val incomingId = item?.mediaId ?: return
        // If we're in a crossfade handoff and the primary deck transitioned to our target
        val isExpectedHandoff = handoffId == incomingId && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO

        if (!isExpectedHandoff) {
            // Unexpected transition - cancel crossfade
            if (crossfadeActive) cancel("primary_transition_${reason}")
            lastPrimaryId = incomingId
            return
        }

        if (!deckAPlayer.playWhenReady) {
            cancel("handoff_paused")
            return
        }

        // Primary deck has transitioned to the next track - complete handoff
        beginHandoff(incomingId)
    }

    private fun beginHandoff(completedId: String) {
        if (handoffStartedAtMs != 0L) return

        // Sync position between decks
        val position = deckBPlayer.currentPosition.coerceAtLeast(0L)
        deckAPlayer.seekTo(position)
        deckAPlayer.volume = baseGain.coerceIn(0f, 1f)
        deckBPlayer.volume = 0f
        deckBPlayer.pause()

        handoffStartedAtMs = SystemClock.elapsedRealtime()
        deckB.markFadingOut()
        Log.i("KiloCrossfade", "handoff_sync id=$completedId positionMs=$position")
    }

    private fun tickHandoff(): Long {
        val expectedId = handoffId ?: return IDLE_TICK_MS

        // Wait for primary deck to be ready at the synced position
        if (deckA.mediaId != expectedId || deckAPlayer.playbackState != Player.STATE_READY) {
            return HANDOFF_TICK_MS
        }

        if (handoffStartedAtMs == 0L) {
            beginHandoff(expectedId)
            return HANDOFF_TICK_MS
        }

        val progress = ((SystemClock.elapsedRealtime() - handoffStartedAtMs).toFloat() / HANDOFF_MS).coerceIn(0f, 1f)
        val gains = EnvelopeGenerator.equalPower(progress)
        // Fade in primary, fade out standby
        deckAPlayer.volume = (baseGain * gains.incoming).coerceIn(0f, 1f)
        deckBPlayer.volume = (baseGain * gains.outgoing).coerceIn(0f, 1f)

        if (progress >= 1f) {
            // Handoff complete
            deckAPlayer.volume = baseGain
            deckB.clear()
            deckB.markComplete()
            val finishedId = handoffId ?: preparedId ?: ""
            handoffId = null
            handoffStartedAtMs = 0L
            fadeStarted = false
            fadeCompleted = false
            fadeStartedAtMs = 0L
            fadeDurationMs = 0L
            crossfadeActive = false
            preparedId = null
            lastPrimaryId = finishedId
            CrossfadeMetrics.finished(finishedId, TransitionMode.CROSSFADE, true)
            Log.i("KiloCrossfade", "handoff_complete id=$finishedId")
        }

        return HANDOFF_TICK_MS
    }

    private var handoffId: String? = null
    private var handoffStartedAtMs = 0L

    fun cancel(reason: String, restorePrimary: Boolean = true) {
        if (preparedId == null && handoffId == null && !fadeStarted && !crossfadeActive) return
        val id = preparedId ?: deckB.mediaId
        clearPreparation()
        if (restorePrimary) {
            deckAPlayer.volume = baseGain.takeIf { it > 0f } ?: 1f
        }
        Log.d("KiloCrossfade", "cancel id=$id reason=$reason")
    }

    private fun clearPreparation() {
        val id = preparedId ?: deckB.mediaId
        deckB.clear()
        PlayerHolder.setStandbyAnalysisMediaId("")
        if (id.isNotBlank()) preloadCoordinator.forget(id)
        preloadCoordinator.decoderPool.clear()
        preparedId = null
        fadeStarted = false
        fadeCompleted = false
        fadeStartedAtMs = 0L
        fadeDurationMs = 0L
        crossfadeActive = false
        handoffId = null
        handoffStartedAtMs = 0L
    }

    fun onStop() = cancel("playback_stopped")

    private val priming: Boolean
        get() = deckB.state == DeckState.PREPARING

    private companion object {
        const val HANDOFF_MS = 200L
        const val HANDOFF_TICK_MS = 20L
        const val FADE_TICK_MS = 16L  // ~60fps for smooth crossfade
        const val IDLE_TICK_MS = 250L
        const val MINIMUM_SAFE_FADE_BUFFER_MS = 1_500L
    }
}