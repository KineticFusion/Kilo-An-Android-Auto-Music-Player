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
 * Deck A remains KILO's queue and session player. Deck B is a single-item standby deck
 * that uses the same resolver/cache and only becomes audible for an overlapping transition.
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
    private var priming = false
    private var primed = false
    private var fadeStartedAtMs = 0L
    private var fadeDurationMs = 0L
    private var handoffId: String? = null
    private var handoffStartedAtMs = 0L
    private var baseGain = 1f
    private var lastPrimaryId = ""
    private var preparationStartedAtMs = 0L
    private var activeDeck = ActiveDeck.A

    init {
        deckBPlayer.volume = 0f
        deckBPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && deckB.mediaId == preparedId) {
                    deckB.markReady()
                    preparationManager.snapshot()
                    StreamPreparationMetrics.prepared(deckB.mediaId, SystemClock.elapsedRealtime() - preparationStartedAtMs, deckB.bufferedMs)
                    beginSilentPrimeIfNeeded()
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
                if (!playWhenReady) deckBPlayer.pause()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (handoffId == deckA.mediaId && playbackState == Player.STATE_READY) beginHandoff()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                handlePrimaryTransition(mediaItem, reason)
            }
        })
    }

    /** Called from KILO's existing player sampler; no extra always-running thread is created. */
    fun tick(): Long {
        tickHandoff()
        if (handoffId != null) return FADE_TICK_MS

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
        if ((priming || fadeStarted) && !deckBPlayer.playWhenReady && deckB.ready) deckBPlayer.play()

        // Keep the hot path small after the overlap starts. Transition planning
        // and cached analysis should not run again on every 20 ms fade tick.
        if (fadeStarted) {
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
            if (progress >= 1f && !fadeCompletionLogged) {
                fadeCompletionLogged = true
                deckB.markActive()
                CrossfadeMetrics.overlapProgress(deckA.mediaId, deckA.remainingMs, deckB.bufferedMs)
            }
            return FADE_TICK_MS
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
        if (TransitionScheduler.shouldPrepare(remaining, preparedId == next.mediaId)) {
            prepareNext(next, plan)
        } else if (preparedId != null && preparedId != next.mediaId) {
            cancel("queue_next_changed")
        }

        if (priming && deckB.ready) {
            val buffered = deckB.bufferedMs
            if (buffered >= TransitionScheduler.MINIMUM_BUFFER_MS + 1_000L) {
                deckBPlayer.pause()
                deckBPlayer.seekTo(0L)
                priming = false
                primed = true
                deckB.markReady()
                preparationManager.snapshot()
                Log.i("KiloCrossfade", "standby_primed id=${deckB.mediaId} bufferedMs=$buffered")
            }
        }

        if (preparedId == next.mediaId && primed && !fadeStarted &&
            TransitionScheduler.shouldStart(remaining, plan.durationMs, deckB.ready, deckB.bufferedMs)
        ) {
            startCrossfade(currentItem, next, plan.durationMs)
        }

        return IDLE_TICK_MS
    }

    private var fadeStarted = false
    private var fadeCompletionLogged = false

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
        priming = false
        primed = false
        fadeStarted = false
        fadeCompletionLogged = false
        fadeDurationMs = plan.durationMs
        deckB.preparing(next)
        deckBPlayer.setPlaybackSpeed(deckAPlayer.playbackParameters.speed)
        deckBPlayer.setSkipSilenceEnabled(deckAPlayer.skipSilenceEnabled)
        LyricsManager.prefetch(next.toSong())
        CrossfadeMetrics.plan(deckA.mediaId, plan.mode, plan.durationMs, plan.reason)
        Log.i("KiloCrossfade", "standby_prepare id=${next.mediaId} title=${next.mediaMetadata.title}")
    }

    private fun beginSilentPrimeIfNeeded() {
        if (preparedId == null || primed || priming || !deckB.ready) return
        deckBPlayer.volume = 0f
        deckBPlayer.seekTo(0L)
        deckBPlayer.play()
        priming = true
        Log.d("KiloCrossfade", "standby_silent_prime id=${deckB.mediaId}")
    }

    private fun startCrossfade(current: MediaItem, next: MediaItem, durationMs: Long) {
        if (!deckB.ready || !primed || !deckAPlayer.isPlaying) return
        baseGain = deckAPlayer.volume.coerceIn(0f, 1f).takeIf { it > 0f } ?: 1f
        fadeDurationMs = durationMs.coerceAtMost(deckA.remainingMs.coerceAtLeast(1L))
        fadeStartedAtMs = SystemClock.elapsedRealtime()
        fadeStarted = true
        fadeCompletionLogged = false
        primed = false
        deckBPlayer.seekTo(0L)
        deckBPlayer.volume = 0f
        deckB.markFadingIn()
        deckBPlayer.play()
        CrossfadeMetrics.started(current.mediaId, next.mediaId, fadeDurationMs)
        Log.i("KiloCrossfade", "overlap_start from=${current.mediaId} to=${next.mediaId} durationMs=$fadeDurationMs curve=equal_power")
    }

    private fun handlePrimaryTransition(item: MediaItem?, reason: Int) {
        val incomingId = item?.mediaId ?: return
        val isExpectedAutomatic = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
            incomingId == preparedId && fadeStarted && deckB.mediaId == incomingId
        if (!isExpectedAutomatic) {
            cancel("primary_transition_${reason}")
            lastPrimaryId = incomingId
            return
        }
        if (!deckAPlayer.playWhenReady) {
            cancel("handoff_paused")
            return
        }
        handoffId = incomingId
        activeDeck = ActiveDeck.B
        mixer.mutePrimary()
        deckBPlayer.play()
        Log.i("KiloCrossfade", "handoff_wait_primary_ready id=$incomingId activeDeck=$activeDeck deckBPositionMs=${deckBPlayer.currentPosition}")
        if (deckAPlayer.playbackState == Player.STATE_READY) beginHandoff()
    }

    private fun beginHandoff() {
        val expectedId = handoffId ?: return
        if (handoffStartedAtMs != 0L) return
        if (deckA.mediaId != expectedId || !deckBPlayer.isPlaying) return
        val continuePlaying = deckAPlayer.playWhenReady
        val position = deckBPlayer.currentPosition.coerceAtLeast(0L)
        deckAPlayer.volume = 0f
        deckAPlayer.seekTo(position)
        if (continuePlaying) deckAPlayer.play()
        baseGain = baseGain.coerceIn(0f, 1f)
        handoffStartedAtMs = SystemClock.elapsedRealtime()
        deckB.markFadingOut()
        Log.i("KiloCrossfade", "handoff_sync id=$expectedId positionMs=$position")
    }

    private fun tickHandoff() {
        val expectedId = handoffId ?: return
        if (deckA.mediaId != expectedId || deckAPlayer.playbackState != Player.STATE_READY) return
        if (handoffStartedAtMs == 0L) {
            beginHandoff()
            return
        }
        val progress = ((SystemClock.elapsedRealtime() - handoffStartedAtMs).toFloat() / HANDOFF_MS).coerceIn(0f, 1f)
        val gains = EnvelopeGenerator.equalPower(progress)
        deckAPlayer.volume = (baseGain * gains.incoming).coerceIn(0f, 1f)
        deckBPlayer.volume = (baseGain * gains.outgoing).coerceIn(0f, 1f)
        if (progress >= 1f) {
            val completedId = expectedId
            deckAPlayer.volume = baseGain
            deckB.clear()
            deckB.markComplete()
            handoffId = null
            handoffStartedAtMs = 0L
            fadeStarted = false
            fadeCompletionLogged = false
            fadeStartedAtMs = 0L
            preparedId = null
            primed = false
            lastPrimaryId = completedId
            activeDeck = ActiveDeck.A
            CrossfadeMetrics.finished(completedId, TransitionMode.CROSSFADE, true)
            Log.i("KiloCrossfade", "handoff_complete id=$completedId activeDeck=$activeDeck")
        }
    }

    fun cancel(reason: String, restorePrimary: Boolean = true) {
        if (preparedId == null && handoffId == null && !fadeStarted) return
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
        activeDeck = ActiveDeck.A
        PlayerHolder.setStandbyAnalysisMediaId("")
        if (id.isNotBlank()) preloadCoordinator.forget(id)
        preloadCoordinator.decoderPool.clear()
        preparedId = null
        priming = false
        primed = false
        fadeStarted = false
        fadeCompletionLogged = false
        fadeStartedAtMs = 0L
        fadeDurationMs = 0L
        handoffId = null
        handoffStartedAtMs = 0L
    }

    fun onStop() = cancel("playback_stopped")

    private companion object {
        const val HANDOFF_MS = 240L
        const val FADE_TICK_MS = 20L
        const val IDLE_TICK_MS = 250L
        const val MINIMUM_SAFE_FADE_BUFFER_MS = 1_500L
    }
}
