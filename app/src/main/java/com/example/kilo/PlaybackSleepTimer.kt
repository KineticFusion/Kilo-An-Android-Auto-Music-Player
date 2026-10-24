package com.example.kilo

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaybackSleepTimerState(
    val minutes: Int? = null,
    val endsAtElapsedRealtimeMs: Long? = null,
    val afterTrackMediaId: String? = null,
    val setFromRadio: Boolean = false
)

/** Timer-only controls for the existing shared player; this never creates or replaces a player. */
object PlaybackSleepTimer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private val _state = MutableStateFlow(PlaybackSleepTimerState())
    val state: StateFlow<PlaybackSleepTimerState> = _state.asStateFlow()

    private var player: Player? = null
    private var timerJob: Job? = null
    private var ignoredTransitionMediaId: String? = null
    private var stoppedTransitionMediaId: String? = null
    private var stoppedTransitionAtMs: Long = 0L

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val nextId = mediaItem?.mediaId ?: return
            synchronized(lock) {
                if (ignoredTransitionMediaId == nextId) {
                    ignoredTransitionMediaId = null
                    return
                }
            }
            finishAfterTrackIfNeeded(nextId)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && _state.value.afterTrackMediaId != null) {
                clear()
            }
        }
    }

    fun attach(sharedPlayer: Player) {
        synchronized(lock) {
            if (player === sharedPlayer) return
            player?.removeListener(playerListener)
            player = sharedPlayer
            sharedPlayer.addListener(playerListener)
        }
    }

    fun setDuration(sharedPlayer: Player, minutes: Int?, fromRadio: Boolean = false) {
        attach(sharedPlayer)
        val safeMinutes = minutes?.takeIf { it in 1..240 }
        synchronized(lock) {
            timerJob?.cancel()
            timerJob = null
            ignoredTransitionMediaId = null
            stoppedTransitionMediaId = null
            stoppedTransitionAtMs = 0L
            if (safeMinutes == null) {
                _state.value = PlaybackSleepTimerState()
                return
            }
            val deadline = SystemClock.elapsedRealtime() + safeMinutes * 60_000L
            _state.value = PlaybackSleepTimerState(
                minutes = safeMinutes,
                endsAtElapsedRealtimeMs = deadline,
                setFromRadio = fromRadio
            )
            timerJob = scope.launch {
                delay(safeMinutes * 60_000L)
                synchronized(lock) {
                    if (_state.value.endsAtElapsedRealtimeMs != deadline) return@synchronized
                    player?.pause()
                    clearLocked()
                }
            }
        }
    }

    fun setAfterCurrentTrack(sharedPlayer: Player): Boolean {
        attach(sharedPlayer)
        val mediaId = sharedPlayer.currentMediaItem?.mediaId?.takeIf(String::isNotBlank) ?: return false
        synchronized(lock) {
            timerJob?.cancel()
            timerJob = null
            ignoredTransitionMediaId = null
            stoppedTransitionMediaId = null
            stoppedTransitionAtMs = 0L
            _state.value = PlaybackSleepTimerState(afterTrackMediaId = mediaId)
        }
        return true
    }

    fun retargetAfterTrackRetry(mediaId: String, ignoreTransitionTo: String) {
        synchronized(lock) {
            val current = _state.value
            if (current.afterTrackMediaId != null && mediaId.isNotBlank()) {
                _state.value = current.copy(afterTrackMediaId = mediaId)
                ignoredTransitionMediaId = ignoreTransitionTo
            }
        }
    }

    fun cancel() {
        synchronized(lock) { clearLocked() }
    }

    fun finishAfterTrackIfNeeded(nextMediaId: String, acknowledgeStop: Boolean = false): Boolean {
        synchronized(lock) {
            val target = _state.value.afterTrackMediaId
            if (target != null && nextMediaId != target) {
                player?.pause()
                clearLocked()
                if (!acknowledgeStop) {
                    stoppedTransitionMediaId = nextMediaId
                    stoppedTransitionAtMs = SystemClock.elapsedRealtime()
                }
                return true
            }
            if (acknowledgeStop && stoppedTransitionMediaId == nextMediaId &&
                SystemClock.elapsedRealtime() - stoppedTransitionAtMs < 5_000L
            ) {
                stoppedTransitionMediaId = null
                stoppedTransitionAtMs = 0L
                return true
            }
            return false
        }
    }

    private fun clear() {
        synchronized(lock) { clearLocked() }
    }

    private fun clearLocked() {
        timerJob?.cancel()
        timerJob = null
        ignoredTransitionMediaId = null
        stoppedTransitionMediaId = null
        stoppedTransitionAtMs = 0L
        _state.value = PlaybackSleepTimerState()
    }
}
