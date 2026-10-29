package com.example.kilo.observability

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Bounded, privacy-safe playback timing. It records identifiers and timings, never stream URLs. */
object PlaybackProfiler {
    private const val TAG = "KiloPlaybackProfiler"
    private const val MAX_TRACES = 256

    private data class Trace(
        val selectedAtMs: Long,
        val source: String,
        val phases: ConcurrentHashMap<String, Long> = ConcurrentHashMap(),
        @Volatile var resolverMs: Long = 0L,
        @Volatile var firstAudioAtMs: Long = 0L
    )

    private val traces = object : LinkedHashMap<String, Trace>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Trace>?): Boolean = size > MAX_TRACES
    }
    @Volatile private var enabled = false

    fun setEnabled(value: Boolean) { enabled = value }

    fun selected(mediaId: String, source: String) {
        if (!enabled || mediaId.isBlank()) return
        synchronized(traces) { traces[mediaId] = Trace(SystemClock.elapsedRealtime(), source) }
        LatencyTracker.begin(mediaId, "tap_to_audio")
        Log.d(TAG, "event=selected mediaId=$mediaId source=$source")
    }

    fun phaseStarted(mediaId: String, phase: String) {
        if (!enabled) return
        trace(mediaId)?.phases?.put(phase, SystemClock.elapsedRealtime())
    }

    fun phaseFinished(mediaId: String, phase: String, success: Boolean, detail: String = "") {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        val trace = trace(mediaId)
        val started = trace?.phases?.remove(phase)
        val duration = started?.let { (now - it).coerceAtLeast(0L) }
        if (duration != null) {
            if (phase == "source_resolution") trace.resolverMs += duration
            LatencyTracker.record(phase, duration)
        }
        Log.d(TAG, "event=phase mediaId=$mediaId name=$phase durationMs=${duration ?: -1} success=$success $detail".trim())
    }

    fun phaseDuration(mediaId: String, phase: String, durationMs: Long, detail: String = "") {
        if (!enabled || mediaId.isBlank()) return
        LatencyTracker.record(phase, durationMs)
        Log.d(TAG, "event=phase mediaId=$mediaId name=$phase durationMs=${durationMs.coerceAtLeast(0L)} $detail".trim())
    }

    fun playerState(mediaId: String, state: String, playWhenReady: Boolean, isPlaying: Boolean, bufferedMs: Long) {
        if (!enabled || mediaId.isBlank()) return
        StabilityMetrics.recordState(state, playWhenReady, isPlaying)
        BufferMetrics.record(state == "BUFFERING", bufferedMs)
    }

    fun firstAudio(mediaId: String, signal: String) {
        if (!enabled || mediaId.isBlank()) return
        val trace = trace(mediaId) ?: return
        synchronized(trace) {
            if (trace.firstAudioAtMs != 0L) return
            val now = SystemClock.elapsedRealtime()
            trace.firstAudioAtMs = now
            val total = (now - trace.selectedAtMs).coerceAtLeast(0L)
            LatencyTracker.finish(mediaId, "tap_to_audio")
            Log.i(TAG, "STARTUP mediaId=$mediaId source=${trace.source} resolverMs=${trace.resolverMs} totalMs=$total signal=$signal")
            synchronized(traces) { traces.remove(mediaId) }
        }
    }

    fun record(event: String, mediaId: String = "", detail: String = "") {
        if (!enabled || event.isBlank()) return
        Log.d(TAG, "event=$event mediaId=$mediaId $detail".trim())
    }

    private fun trace(mediaId: String): Trace? = synchronized(traces) { traces[mediaId] }
}

/** Keeps recent latency samples in memory for a compact diagnostic summary. */
object LatencyTracker {
    private const val MAX_SAMPLES_PER_PHASE = 128
    private val starts = ConcurrentHashMap<String, Long>()
    private val samples = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun begin(mediaId: String, phase: String) {
        if (mediaId.isNotBlank()) {
            starts["$mediaId:$phase"] = SystemClock.elapsedRealtime()
            if (starts.size > 512) starts.keys.firstOrNull()?.let(starts::remove)
        }
    }

    fun finish(mediaId: String, phase: String): Long? {
        val start = starts.remove("$mediaId:$phase") ?: return null
        val duration = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
        record(phase, duration)
        return duration
    }

    fun record(phase: String, durationMs: Long) {
        if (phase.isBlank()) return
        val bucket = samples.computeIfAbsent(phase) { ArrayDeque() }
        synchronized(bucket) {
            if (bucket.size == MAX_SAMPLES_PER_PHASE) bucket.removeFirst()
            bucket.addLast(durationMs.coerceAtLeast(0L))
        }
    }

    fun snapshot(): Map<String, List<Long>> = samples.mapValues { (_, values) -> synchronized(values) { values.toList() } }
}

object SourceMetrics {
    private val attempts = AtomicLong()
    private val cacheHits = AtomicLong()
    private val failures = AtomicLong()
    private val expiredResponses = AtomicLong()

    fun resolverStarted(cacheHit: Boolean) {
        attempts.incrementAndGet()
        if (cacheHit) cacheHits.incrementAndGet()
    }

    fun resolverFinished(success: Boolean, failure: String? = null) {
        if (!success) failures.incrementAndGet()
        if (failure?.contains("403") == true || failure?.contains("410") == true) expiredResponses.incrementAndGet()
    }

    fun snapshot(): Map<String, Long> = mapOf(
        "attempts" to attempts.get(), "cacheHits" to cacheHits.get(),
        "failures" to failures.get(), "403_410" to expiredResponses.get()
    )
}

object BufferMetrics {
    private val rebufferEvents = AtomicLong()
    private val lastBufferedMs = AtomicLong()

    fun record(isBuffering: Boolean, bufferedMs: Long) {
        if (isBuffering) rebufferEvents.incrementAndGet()
        lastBufferedMs.set(bufferedMs.coerceAtLeast(0L))
    }

    fun snapshot(): Map<String, Long> = mapOf("rebufferEvents" to rebufferEvents.get(), "lastBufferedMs" to lastBufferedMs.get())
}

object QueueMetrics {
    private val selections = AtomicLong()
    private val transitions = AtomicLong()

    fun selected() { selections.incrementAndGet() }
    fun transitioned() { transitions.incrementAndGet() }
    fun snapshot(): Map<String, Long> = mapOf("selections" to selections.get(), "transitions" to transitions.get())
}

object AndroidAutoMetrics {
    private val callbacks = AtomicLong()
    private val slowCallbacks = AtomicLong()

    fun callback(durationMs: Long) {
        callbacks.incrementAndGet()
        if (durationMs > 200L) slowCallbacks.incrementAndGet()
    }

    fun snapshot(): Map<String, Long> = mapOf("callbacks" to callbacks.get(), "slowCallbacks" to slowCallbacks.get())
}

object StabilityMetrics {
    private val idleStates = AtomicLong()
    private val playerErrors = AtomicLong()

    fun recordState(state: String, playWhenReady: Boolean, isPlaying: Boolean) {
        if (state == "IDLE" && playWhenReady && !isPlaying) idleStates.incrementAndGet()
    }

    fun playerError() { playerErrors.incrementAndGet() }
    fun snapshot(): Map<String, Long> = mapOf("unexpectedIdleStates" to idleStates.get(), "playerErrors" to playerErrors.get())
}
