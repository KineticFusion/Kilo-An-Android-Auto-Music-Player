package com.example.kilo.playback.crossfade

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

data class TransitionMetricsSnapshot(
    val plans: Long,
    val completed: Long,
    val failed: Long,
    val lastMode: TransitionMode?,
    val lastDurationMs: Long,
    val lastReason: String
)

object CrossfadeMetrics {
    private val plans = AtomicLong()
    private val completed = AtomicLong()
    private val failedCount = AtomicLong()
    @Volatile private var lastMode: TransitionMode? = null
    @Volatile private var lastDurationMs = 0L
    @Volatile private var lastReason = "none"

    fun started(outgoingId: String, incomingId: String, durationMs: Long) {
        Log.i("KiloCrossfade", "overlap_started outgoing=${outgoingId.hashCode()} incoming=${incomingId.hashCode()} durationMs=$durationMs")
    }

    fun overlapProgress(trackId: String, remainingMs: Long, standbyBufferedMs: Long) {
        Log.v("KiloCrossfade", "overlap_progress active=${trackId.hashCode()} remainingMs=$remainingMs standbyBufferedMs=$standbyBufferedMs")
    }

    fun failed(trackId: String, stage: String) {
        failedCount.incrementAndGet()
        lastReason = "failed_$stage"
        Log.w("KiloCrossfade", "overlap_failed track=${trackId.hashCode()} stage=$stage")
    }

    fun plan(trackId: String, mode: TransitionMode, durationMs: Long, reason: String) {
        plans.incrementAndGet()
        lastMode = mode
        lastDurationMs = durationMs
        lastReason = reason
        Log.d("KiloTransitions", "transition track=${trackId.hashCode()} mode=$mode durationMs=$durationMs reason=$reason")
    }

    fun finished(trackId: String, mode: TransitionMode, success: Boolean) {
        if (success) completed.incrementAndGet() else failedCount.incrementAndGet()
        Log.d("KiloTransitions", "transition_complete track=${trackId.hashCode()} mode=$mode success=$success")
    }

    fun snapshot() = TransitionMetricsSnapshot(plans.get(), completed.get(), failedCount.get(), lastMode, lastDurationMs, lastReason)
}
