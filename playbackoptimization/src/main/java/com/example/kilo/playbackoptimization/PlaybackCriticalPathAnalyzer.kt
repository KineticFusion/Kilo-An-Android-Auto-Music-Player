package com.example.kilo.playbackoptimization

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player
import java.util.concurrent.ConcurrentHashMap

/** Debug-only, observer-only timing for the existing playback pipeline. */
object PlaybackCriticalPathAnalyzer {
    private const val TAG = "PlaybackCriticalPath"
    private const val MAX_TRACES = 128
    private const val TRACE_TTL_MS = 10 * 60 * 1000L
    private val reportPhases = listOf(
        "ui_processing",
        "queue_creation",
        "track_lookup",
        "metadata_retrieval_and_media_item_build",
        "source_resolution",
        "url_validation",
        "media_source_load_start",
        "player_prepare_call",
        "player_ready_from_click",
        "initial_buffer",
        "first_audio_frame"
    )

    private class Trace(
        val mediaId: String,
        @Volatile var source: String,
        val selectedAtMs: Long,
        val selectedAtWallMs: Long
    ) {
        val phaseDurations = ConcurrentHashMap<String, Long>()
        val phaseStarts = ConcurrentHashMap<String, Long>()
        @Volatile var bufferingStartedAtMs = -1L
        @Volatile var lastReadyAtMs = -1L
        @Volatile var audioReported = false
        @Volatile var lastFocusSuppression = false
    }

    private val traces = ConcurrentHashMap<String, Trace>()
    @Volatile private var enabled = false

    fun initialize(debugEnabled: Boolean) {
        enabled = debugEnabled
        if (!debugEnabled) traces.clear()
    }

    /** Called at the user's selection boundary. Repeated wrapper callbacks may retain this trace. */
    fun userSelected(mediaId: String, source: String, preserveRecent: Boolean = false) {
        if (!enabled || mediaId.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        val existing = traces[mediaId]
        if (preserveRecent && existing != null && now - existing.selectedAtMs < 10_000L) {
            existing.source = source
            return
        }
        traces[mediaId] = Trace(mediaId, source, now, System.currentTimeMillis())
        prune(now)
        Log.i(TAG, "event=user_click mediaId=$mediaId source=$source elapsedRealtimeMs=$now wallTimeMs=${System.currentTimeMillis()}")
    }

    fun phaseStarted(mediaId: String, phase: String) {
        if (!enabled || mediaId.isBlank()) return
        val trace = traces[mediaId] ?: return
        trace.phaseStarts[phase] = SystemClock.elapsedRealtime()
    }

    fun phaseFinished(mediaId: String, phase: String, success: Boolean? = null, detail: String = "") {
        if (!enabled || mediaId.isBlank()) return
        val trace = traces[mediaId] ?: return
        val now = SystemClock.elapsedRealtime()
        val started = trace.phaseStarts.remove(phase)
        if (started != null) trace.phaseDurations[phase] = (now - started).coerceAtLeast(0L)
        val duration = trace.phaseDurations[phase] ?: -1L
        Log.i(TAG, "event=phase mediaId=$mediaId name=$phase durationMs=$duration success=$success detail=$detail elapsedFromClickMs=${now - trace.selectedAtMs}")
    }

    fun phaseDuration(mediaId: String, phase: String, durationMs: Long, detail: String = "") {
        if (!enabled || mediaId.isBlank()) return
        val trace = traces[mediaId] ?: return
        val duration = durationMs.coerceAtLeast(0L)
        trace.phaseDurations[phase] = duration
        Log.i(TAG, "event=phase mediaId=$mediaId name=$phase durationMs=$duration detail=$detail elapsedFromClickMs=${SystemClock.elapsedRealtime() - trace.selectedAtMs}")
    }

    fun accumulatePhaseDuration(mediaId: String, phase: String, durationMs: Long, detail: String = "") {
        if (!enabled || mediaId.isBlank()) return
        val trace = traces[mediaId] ?: return
        val duration = durationMs.coerceAtLeast(0L)
        val total = trace.phaseDurations.merge(phase, duration, Long::plus) ?: duration
        Log.i(TAG, "event=phase mediaId=$mediaId name=$phase accumulatedDurationMs=$total detail=$detail elapsedFromClickMs=${SystemClock.elapsedRealtime() - trace.selectedAtMs}")
    }

    fun playerState(mediaId: String?, state: Int, playWhenReady: Boolean, isPlaying: Boolean) {
        if (!enabled || mediaId.isNullOrBlank()) return
        val trace = traces[mediaId] ?: return
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_BUFFERING -> if (trace.bufferingStartedAtMs < 0L) trace.bufferingStartedAtMs = now
            Player.STATE_READY -> {
                trace.lastReadyAtMs = now
                val bufferingStarted = trace.bufferingStartedAtMs
                if (bufferingStarted >= 0L) {
                    phaseDuration(mediaId, "initial_buffer", now - bufferingStarted, "state=READY")
                    trace.bufferingStartedAtMs = -1L
                }
                trace.phaseDurations.putIfAbsent("player_ready_from_click", (now - trace.selectedAtMs).coerceAtLeast(0L))
            }
        }
        Log.d(TAG, "event=player_state mediaId=$mediaId state=${stateName(state)} playWhenReady=$playWhenReady isPlaying=$isPlaying elapsedFromClickMs=${now - trace.selectedAtMs}")
    }

    /** Records interruption causes without changing play/pause intent. */
    fun playWhenReadyChanged(mediaId: String?, value: Boolean, reason: Int, state: Int, suppression: Int, positionMs: Long, bufferedMs: Long) {
        if (!enabled || mediaId.isNullOrBlank()) return
        val reasonName = when (reason) {
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user_request"
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio_focus_loss"
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "audio_becoming_noisy"
            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
            Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end_of_media_item"
            Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "suppressed_too_long"
            else -> "unknown"
        }
        Log.i(TAG, "event=play_intent mediaId=$mediaId value=$value reason=$reasonName reasonCode=$reason state=${stateName(state)} suppression=$suppression positionMs=$positionMs bufferedMs=$bufferedMs")
    }

    fun suppressionChanged(mediaId: String?, suppression: Int, playWhenReady: Boolean, isPlaying: Boolean, positionMs: Long) {
        if (!enabled || mediaId.isNullOrBlank()) return
        val trace = traces[mediaId]
        val focusSuppressed = suppression == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        val event = when {
            focusSuppressed -> "audio_focus_suppressed"
            trace?.lastFocusSuppression == true && suppression == Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "audio_focus_restored"
            else -> "playback_suppression_changed"
        }
        if (trace != null) trace.lastFocusSuppression = focusSuppressed
        Log.i(TAG, "event=$event mediaId=$mediaId suppression=$suppression playWhenReady=$playWhenReady isPlaying=$isPlaying positionMs=$positionMs")
    }

    /** Media3's audio position callback marks when decoded audio actually starts advancing. */
    fun firstAudioFrame(mediaId: String?, signal: String = "media3_audio_position_advancing") {
        if (!enabled || mediaId.isNullOrBlank()) return
        val trace = traces[mediaId] ?: return
        synchronized(trace) {
            if (trace.audioReported) return
            trace.audioReported = true
            val now = SystemClock.elapsedRealtime()
            val total = (now - trace.selectedAtMs).coerceAtLeast(0L)
            trace.phaseDurations["first_audio_frame"] = total
            val phases = reportPhases.joinToString(",") { phase ->
                "$phase:${trace.phaseDurations[phase]?.let { "${it}ms" } ?: "unmeasured"}"
            }
            Log.i(
                TAG,
                "CRITICAL_PATH_REPORT mediaId=$mediaId source=${trace.source} selectedAtWallMs=${trace.selectedAtWallMs} " +
                    "totalClickToAudioMs=$total phases={$phases} firstAudioSignal=$signal"
            )
        }
    }

    /** Keep the trace after first audio so later pauses/buffers/errors remain attributable. */
    fun playerError(mediaId: String?, errorCode: Int, errorName: String, positionMs: Long, state: Int) {
        if (!enabled || mediaId.isNullOrBlank()) return
        val trace = traces[mediaId] ?: return
        Log.e(
            TAG,
            "event=player_error mediaId=$mediaId errorCode=$errorCode errorName=$errorName " +
                "positionMs=$positionMs state=${stateName(state)} elapsedFromClickMs=${SystemClock.elapsedRealtime() - trace.selectedAtMs}"
        )
    }

    fun mediaSourceLoadStarted(mediaId: String?, dataType: Int, trackType: Int) {
        if (!enabled || mediaId.isNullOrBlank()) return
        val trace = traces[mediaId] ?: return
        val elapsed = (SystemClock.elapsedRealtime() - trace.selectedAtMs).coerceAtLeast(0L)
        trace.phaseDurations.putIfAbsent("media_source_load_start", elapsed)
        Log.i(TAG, "event=media_source_load_started mediaId=$mediaId elapsedFromClickMs=$elapsed dataType=$dataType trackType=$trackType note=Media3-load-boundary")
    }

    private fun prune(now: Long) {
        traces.entries.removeIf { now - it.value.selectedAtMs > TRACE_TTL_MS }
        if (traces.size > MAX_TRACES) {
            traces.values.sortedBy { it.selectedAtMs }.take(traces.size - MAX_TRACES).forEach { traces.remove(it.mediaId, it) }
        }
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }
}
