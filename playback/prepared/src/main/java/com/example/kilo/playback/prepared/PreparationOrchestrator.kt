package com.example.kilo.playback.prepared

import android.content.Context
import android.os.SystemClock
import com.example.kilo.playbackoptimization.AndroidAutoOptimizer
import com.example.kilo.playbackoptimization.ArtworkWarmupManager
import com.example.kilo.playbackoptimization.MetadataCacheManager
import com.example.kilo.playbackoptimization.PlaybackMetricsManager
import com.example.kilo.playbackoptimization.PlayableTrackSnapshotCache
import com.example.kilo.playbackoptimization.PreparedTrack as LegacyPreparedTrack
import com.example.kilo.playbackoptimization.PreparedTrackScheduler as LegacyScheduler
import com.example.kilo.playbackoptimization.SourceWarmupManager
import com.example.kilo.playbackoptimization.SourceWarmupResolver
import com.example.kilo.playbackoptimization.StreamPreparationPool
import com.example.kilo.playbackoptimization.WarmedSourceMetadata
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException

/** Owns visible-list preparation policy and delegates network work to KILO's shared deduplicating worker. */
object PreparationOrchestrator {
    @Volatile private var resolver: SourceWarmupResolver? = null
    @Volatile private var sourceCache: ((String) -> WarmedSourceMetadata?)? = null
    private val queuedRequests = ConcurrentHashMap.newKeySet<String>()
    private val worker = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(24),
        { task -> Thread(task, "KiloVisibleTrackPreparation").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun install(
        sourceResolver: SourceWarmupResolver,
        sourceLookup: (String) -> WarmedSourceMetadata?
    ) {
        resolver = sourceResolver
        sourceCache = sourceLookup
    }

    fun prepareVisible(
        context: Context,
        candidates: List<LegacyPreparedTrack>,
        section: String,
        limit: Int
    ) {
        val app = context.applicationContext
        val selected = candidates.asSequence()
            .filter { it.mediaId.isNotBlank() && !it.mediaId.startsWith("radio:") }
            .distinctBy { it.mediaId }
            .take(limit.coerceIn(1, 20))
            .toList()

        if (selected.isEmpty()) return
        val requestKey = "$section:${selected.joinToString("|") { it.mediaId }}"
        if (!queuedRequests.add(requestKey)) return
        try {
            worker.execute {
                try {
                    prepareSelected(app, selected, section)
                } finally {
                    queuedRequests.remove(requestKey)
                }
            }
        } catch (_: RejectedExecutionException) {
            queuedRequests.remove(requestKey)
            PlaybackMetricsManager.record(
                "prepared_orchestrator_queue_full",
                details = "section=$section visible=${selected.size}"
            )
        }
    }

    private fun prepareSelected(
        app: Context,
        selected: List<LegacyPreparedTrack>,
        section: String
    ) {
        val started = SystemClock.elapsedRealtime()
        selected.forEach { legacy ->
            val track = PreparedTrackFactory.fromLegacy(legacy)
            PreparedTrackPool.put(track, PreparedReadinessState.DISCOVERED)
        }

        val sourceResolver = resolver ?: return
        val lookup = sourceCache ?: return

        selected.forEach { legacy ->
            PreparedTrackRegistry.register(PreparedTrackFactory.fromLegacy(legacy))
            PreparedTrackPool.mark(legacy.mediaId, PreparedReadinessState.PREPARING)
            MetadataCacheManager.put(legacy)
            PlayableTrackSnapshotCache.putVisible(legacy)
            if (legacy.artworkUri.isNotBlank()) {
                ArtworkWarmupManager.warm(app, legacy.artworkUri, AndroidAutoOptimizer.artworkMaxPixels(app))
            }
            val cached = runCatching { lookup(legacy.mediaId) }.getOrNull()
                ?: StreamPreparationPool.get(legacy.mediaId)
            if (cached?.isValid() == true) {
                SourceWarmupManager.noteAlreadyResolved(legacy.mediaId, cached)
                PreparedPlaybackLayer.get(legacy.mediaId)
            } else {
                PreparedTrackRegistry.markPreparing(legacy.mediaId)
                PreparedTrackPool.mark(legacy.mediaId, PreparedReadinessState.PREPARING)
                LegacyScheduler.schedule(legacy.mediaId, sourceResolver)
            }
        }
        PlaybackMetricsManager.record(
            "prepared_orchestrator_visible",
            durationMs = SystemClock.elapsedRealtime() - started,
            details = "section=$section visible=${selected.size} worker=${Thread.currentThread().name}"
        )
    }
}

private fun WarmedSourceMetadata.isValid(): Boolean =
    com.example.kilo.playbackoptimization.SourceHealthValidator.isUsable(this)
