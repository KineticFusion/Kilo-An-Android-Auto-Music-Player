package com.example.kilo

import android.os.SystemClock
import android.os.Debug
import android.util.Log
import android.view.Choreographer
import com.example.kilo.androidauto.AndroidAutoCache
import com.example.kilo.androidauto.ArtworkCacheManager
import com.example.kilo.observability.AndroidAutoMetrics
import com.example.kilo.observability.BufferMetrics
import com.example.kilo.observability.QueueMetrics
import com.example.kilo.observability.SourceMetrics
import com.example.kilo.observability.StabilityMetrics
import com.example.kilo.playbackoptimization.MetadataCacheManager
import com.example.kilo.playbackoptimization.PlaybackMetricsManager
import com.example.kilo.playbackoptimization.PreparedTrackStore
import com.example.kilo.playback.stability.PlaybackWatchdog
import com.example.kilo.performance.mainthread.MainThreadMonitor
import com.example.kilo.startup.LaunchProfiler

/** Debug-only startup frame monitor; it observes frame gaps and never blocks the UI thread. */
internal object PlaybackMainThreadMonitor : Choreographer.FrameCallback {
    private const val TAG = "KiloMainThread"
    private const val MONITOR_WINDOW_MS = 15_000L
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private var startedAtMs = 0L
    private var previousFrameNs = 0L
    private var lastFrameLogAtMs = 0L
    private var lastSlowLogAtMs = 0L
    private var lastStallLogAtMs = 0L

    fun start() {
        if (!BuildConfig.DEBUG || !started.compareAndSet(false, true)) return
        MainThreadMonitor.start()
        startedAtMs = SystemClock.elapsedRealtime()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        val now = SystemClock.elapsedRealtime()
        if (previousFrameNs == 0L) {
            Log.i(TAG, "firstFrameFromLaunchMs=${now - startedAtMs}")
        } else {
            val gapMs = (frameTimeNanos - previousFrameNs).coerceAtLeast(0L) / 1_000_000L
            when {
                gapMs >= 100L && now - lastStallLogAtMs >= 2_000L -> {
                    lastStallLogAtMs = now
                    Log.e(TAG, "mainThreadFrameGapMs=$gapMs thresholdMs=100")
                }
                gapMs >= 50L && now - lastSlowLogAtMs >= 2_000L -> {
                    lastSlowLogAtMs = now
                    Log.w(TAG, "mainThreadFrameGapMs=$gapMs thresholdMs=50")
                }
                gapMs > 16L && now - lastFrameLogAtMs >= 5_000L -> {
                    lastFrameLogAtMs = now
                    Log.d(TAG, "mainThreadFrameGapMs=$gapMs thresholdMs=16")
                }
            }
        }
        previousFrameNs = frameTimeNanos
        if (now - startedAtMs < MONITOR_WINDOW_MS) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
}

/** Compact diagnostics export for stability, cache, Android Auto, and process-memory checks. */
internal object PlaybackPerformanceDashboard {
    fun report(): String {
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        val gcCount = runCatching { Debug.getRuntimeStat("art.gc-count")?.toLong() }.getOrNull() ?: -1L
        val samples = PlaybackWatchdog.recent().joinToString(";") { sample ->
            "${sample.mediaId}:${sample.playerState}@${sample.positionMs}+${sample.bufferedMs}" +
                ":pwr=${sample.playWhenReady}:valid=${sample.sourceValid}:src=${sample.sourceState}:q=${sample.queueIndex}" +
                ":bw=${sample.bandwidthKbps}k:tl=${sample.timelineWindows}/${sample.timelinePeriods}@${sample.timelineRevision}" +
                ":decoder=${sample.decoderName}/${sample.decoderFaulted}"
        }
        return "performanceDashboard memoryUsedBytes=$usedBytes heapMaxBytes=${runtime.maxMemory()} " +
            "nativeHeapBytes=${Debug.getNativeHeapAllocatedSize()} gcCount=$gcCount " +
            "artworkEntries=${ArtworkCacheManager.cachedMemoryEntries()} artworkBytes=${ArtworkCacheManager.cachedMemoryBytes()} " +
            "trackEntries=${PlaybackRepository.cacheEntryCounts()} metadataEntries=${MetadataCacheManager.cachedEntryCount()} " +
            "preparedEntries=${PreparedTrackStore.snapshot().size} aaCachedItems=${AndroidAutoCache.cachedItemCount()} " +
            "streamCache=${PlaybackRepository.streamCacheMetrics()} source=${SourceMetrics.snapshot()} " +
            "buffer=${BufferMetrics.snapshot()} stability=${StabilityMetrics.snapshot()} " +
            "queue=${QueueMetrics.snapshot()} androidAuto=${AndroidAutoMetrics.snapshot()} " +
            "queueItems=${PlaybackReliabilityLayer.activeQueueItemCount()} " +
            "optimizationEvents=${PlaybackMetricsManager.report()} launch=${LaunchProfiler.report()} " +
            "healthSamples=${PlaybackWatchdog.recent().size} [$samples]"
    }

    fun logSnapshot(reason: String) {
        if (BuildConfig.DEBUG) Log.i("KiloPerformanceDashboard", "reason=$reason ${report()}")
    }
}
