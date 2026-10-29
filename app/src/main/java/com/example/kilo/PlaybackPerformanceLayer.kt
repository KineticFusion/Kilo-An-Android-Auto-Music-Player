package com.example.kilo

import android.content.Context
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.kilo.streaming.AudioCache
import com.example.kilo.streaming.FastStream
import com.example.kilo.streaming.StreamInfo
import com.example.kilo.playbackoptimization.PlaybackOptimizer
import com.example.kilo.playbackoptimization.PlaybackCriticalPathAnalyzer
import com.example.kilo.playbackoptimization.PlaybackMetricsManager
import com.example.kilo.playbackoptimization.PreparedTrackFactory
import com.example.kilo.playbackoptimization.PreparedTrackRegistry
import com.example.kilo.playbackoptimization.PreparedTrackStatus
import com.example.kilo.playbackoptimization.SourceLifecycleManager
import com.example.kilo.playbackoptimization.SourceLifecycleState
import com.example.kilo.playbackoptimization.PreparedTrackStore
import com.example.kilo.playbackoptimization.SourceWarmupResolver
import com.example.kilo.playbackoptimization.WarmedSourceMetadata
import com.example.kilo.playback.source.SourceRefreshEngine
import com.example.kilo.observability.PlaybackProfiler
import com.example.kilo.observability.QueueMetrics
import com.example.kilo.observability.SourceMetrics
import com.example.kilo.observability.StabilityMetrics
import com.example.kilo.playback.prepared.PreparedPlaybackPlatform
import com.example.kilo.playback.prepared.ClickToPlayCoordinator
import com.example.kilo.playback.prepared.PreparationOrchestrator
import com.example.kilo.playback.prepared.PreparedPlaybackLayer
import com.example.kilo.playback.warmup.ScreenWarmupManager
import com.example.kilo.playback.warmup.SearchWarmupManager
import com.example.kilo.playback.warmup.PlaylistWarmupManager
import com.example.kilo.playback.warmup.AlbumWarmupManager
import com.example.kilo.playback.warmup.RadioWarmupManager
import com.example.kilo.playback.warmup.MoodWarmupManager
import com.example.kilo.playback.stability.BufferGuard
import com.example.kilo.playback.stability.ContinuityManager
import com.example.kilo.playback.stability.RecoveryCoordinator
import com.example.kilo.playback.stability.StreamContinuityCache
import com.example.kilo.playback.cache.QueueSnapshotCache
import com.example.kilo.androidauto.performance.AndroidAutoWarmupManager
import com.example.kilo.androidauto.performance.AndroidAutoPerformanceMode
import com.example.kilo.androidauto.performance.AndroidAutoQueueMirror
import com.example.kilo.startup.LaunchProfiler
import com.example.kilo.startup.StartupOrchestrator
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

/**
 * Debug-only timing markers for the existing playback path. This records timings
 * without changing player commands, stream selection, or queue behavior.
 */
object PlaybackStartupMetrics {
    private const val TAG = "KiloPlaybackProfiler"

    private data class ResolverAttempt(val startedAtMs: Long, val cacheHit: Boolean)

    private class Sample(
        val trackId: String,
        val source: String,
        val selectedAtElapsedMs: Long,
        val selectedAtWallMs: Long,
        val selectionOrdinal: Int
    ) {
        val resolverStarts = java.util.ArrayDeque<ResolverAttempt>()
        var resolverAttempts = 0
        var resolverDurationMs = 0L
        var serviceStartCallDurationMs = -1L
        var mediaItemDurationMs = -1L
        var queueSize = -1
        var setMediaItemsDurationMs = -1L
        var prepareCallDurationMs = -1L
        var bufferingStartedAtMs = -1L
        var readyAtMs = -1L
        var firstAudioAtMs = -1L
    }

    private val samples = ConcurrentHashMap<String, Sample>()
    private val selectionCount = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var applicationStartedAtMs = 0L

    fun applicationStarted() {
        PlaybackProfiler.setEnabled(BuildConfig.DEBUG)
        PlaybackCriticalPathAnalyzer.initialize(BuildConfig.DEBUG)
        if (!BuildConfig.DEBUG) return
        applicationStartedAtMs = SystemClock.elapsedRealtime()
        selectionCount.set(0)
    }

    fun selected(
        trackId: String,
        source: String,
        selectedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
        selectedAtWallMs: Long = System.currentTimeMillis()
    ) {
        if (!BuildConfig.DEBUG || trackId.isBlank()) return
        PlaybackCriticalPathAnalyzer.userSelected(trackId, source, preserveRecent = true)
        val existing = samples[trackId]
        val isQueueTransition = source.equals("automatic_queue", ignoreCase = true)
        if (isQueueTransition) QueueMetrics.transitioned()
        if (existing != null && isQueueTransition) {
            PlaybackProfiler.record("track_transition", trackId, "source=$source")
            return
        }
        val ordinal = selectionCount.incrementAndGet()
        val sample = Sample(trackId, source, selectedAtElapsedMs, selectedAtWallMs, ordinal)
        samples[trackId] = sample
        PlaybackProfiler.selected(trackId, source)
        if (!isQueueTransition) QueueMetrics.selected()
        Log.i(
            TAG,
            "event=selected trackId=" + trackId +
                " source=" + source +
                " selectionOrdinalSinceLaunch=" + ordinal +
                " appUptimeAtSelectionMs=" + (if (applicationStartedAtMs > 0L) selectedAtElapsedMs - applicationStartedAtMs else -1L) +
                " songSelectionTimestampMs=" + selectedAtWallMs +
                " elapsedRealtimeMs=" + selectedAtElapsedMs
        )
        if (samples.size > 128) {
            samples.keys.filter { it != trackId }.take(32).forEach { samples.remove(it) }
        }
    }

    fun resolverStarted(trackId: String, cacheHit: Boolean) {
        PlaybackCriticalPathAnalyzer.phaseStarted(trackId, "source_resolution")
        PlaybackProfiler.phaseStarted(trackId, "source_resolution")
        SourceMetrics.resolverStarted(cacheHit)
        SourceLifecycleManager.transition(trackId, SourceLifecycleState.PREPARING)
        val sample = sample(trackId) ?: return
        synchronized(sample) {
            sample.resolverStarts.addLast(ResolverAttempt(SystemClock.elapsedRealtime(), cacheHit))
            sample.resolverAttempts += 1
            Log.i(TAG, "event=resolver_start trackId=" + trackId +
                " attempt=" + sample.resolverAttempts + " cacheHit=" + cacheHit)
        }
    }

    fun resolverFinished(trackId: String, failure: String? = null) {
        PlaybackCriticalPathAnalyzer.phaseFinished(trackId, "source_resolution", success = failure.isNullOrBlank(), detail = "failure=${failure.orEmpty()}")
        PlaybackProfiler.phaseFinished(trackId, "source_resolution", failure.isNullOrBlank(), "failure=${failure.orEmpty()}")
        SourceMetrics.resolverFinished(failure.isNullOrBlank(), failure)
        if (failure.isNullOrBlank()) {
            val source = ResolvedTrackCache.peek(trackId)?.let { info ->
                WarmedSourceMetadata(
                    streamUrl = info.url,
                    requestHeaders = info.requestHeaders,
                    codec = info.codec,
                    bitrateKbps = info.bitrateKbps,
                    durationMs = info.durationMs,
                    sourceExpiresAtEpochMs = info.expiresAtEpochMs
                )
            }
            if (source != null) PreparedTrackRegistry.markReady(trackId, source)
            if (PreparedTrackStore.get(trackId)?.status != PreparedTrackStatus.PLAYING) {
                SourceLifecycleManager.transition(trackId, SourceLifecycleState.READY)
            }
        } else {
            PreparedTrackRegistry.markFailed(trackId)
            SourceLifecycleManager.transition(trackId, SourceLifecycleState.FAILED)
        }
        val sample = sample(trackId) ?: return
        synchronized(sample) {
            val attempt = if (sample.resolverStarts.isEmpty()) null else sample.resolverStarts.removeFirst()
            val duration = attempt?.let { (SystemClock.elapsedRealtime() - it.startedAtMs).coerceAtLeast(0L) } ?: -1L
            if (duration >= 0L) sample.resolverDurationMs += duration
            Log.i(TAG, "event=resolver_end trackId=" + trackId +
                " attempt=" + sample.resolverAttempts +
                " durationMs=" + duration +
                " resolverTotalMs=" + sample.resolverDurationMs +
                " cacheHit=" + (attempt?.cacheHit ?: false) +
                " result=" + (if (failure.isNullOrBlank()) "ready" else "failed"))
        }
    }

    fun serviceStartCall(trackId: String, durationMs: Long) {
        PlaybackCriticalPathAnalyzer.phaseDuration(trackId, "player_service_start", durationMs)
        val sample = sample(trackId) ?: return
        synchronized(sample) {
            sample.serviceStartCallDurationMs = durationMs.coerceAtLeast(0L)
            Log.i(TAG, "event=service_start_call trackId=" + trackId +
                " durationMs=" + sample.serviceStartCallDurationMs)
        }
    }

    fun mediaItemsBuilt(trackId: String, durationMs: Long, queueSize: Int) {
        PlaybackCriticalPathAnalyzer.phaseDuration(trackId, "queue_media_item_build", durationMs, "items=$queueSize")
        PlaybackProfiler.phaseDuration(trackId, "media_item_creation", durationMs, "items=$queueSize")
        val sample = sample(trackId) ?: return
        synchronized(sample) {
            sample.mediaItemDurationMs = durationMs.coerceAtLeast(0L)
            sample.queueSize = queueSize
            Log.i(TAG, "event=media_items_built trackId=" + trackId +
                " mediaItemDurationMs=" + sample.mediaItemDurationMs +
                " queueSize=" + queueSize)
        }
    }

    fun mediaItemsSet(trackId: String, durationMs: Long, operation: String = "setMediaItems") {
        PlaybackCriticalPathAnalyzer.phaseDuration(trackId, "player_set_media_items", durationMs, "operation=$operation")
        PlaybackProfiler.phaseDuration(trackId, "player_set_media_items", durationMs, "operation=$operation")
        val sample = sample(trackId) ?: return
        synchronized(sample) {
            sample.setMediaItemsDurationMs = durationMs.coerceAtLeast(0L)
            Log.i(TAG, "event=player_media_items trackId=" + trackId +
                " operation=" + operation +
                " durationMs=" + sample.setMediaItemsDurationMs)
        }
    }

    fun mediaItemsDelivered(trackId: String, source: String, durationMs: Long) {
        AndroidAutoWarmupManager.noteCarConnected()
        AndroidAutoPerformanceMode.callbackFinished(durationMs)
        PlaybackProfiler.phaseDuration(trackId, "android_auto_delivery", durationMs, "source=$source")
        val sample = sample(trackId) ?: return
        synchronized(sample) {
            Log.i(TAG, "event=media_items_delivered trackId=" + trackId +
                " source=" + source + " callbackDurationMs=" + durationMs.coerceAtLeast(0L))
        }
    }

    fun prepareCall(trackId: String, durationMs: Long) {
        PlaybackCriticalPathAnalyzer.phaseDuration(trackId, "player_prepare_call", durationMs)
        PlaybackProfiler.phaseDuration(trackId, "player_prepare_call", durationMs)
        val sample = sample(trackId) ?: return
        synchronized(sample) {
            sample.prepareCallDurationMs = durationMs.coerceAtLeast(0L)
            Log.i(TAG, "event=player_prepare trackId=" + trackId +
                " prepareDurationMs=" + sample.prepareCallDurationMs)
        }
    }

    fun playerState(trackId: String?, playbackState: Int, playWhenReady: Boolean, isPlaying: Boolean) {
        val id = trackId ?: return
        PlaybackCriticalPathAnalyzer.playerState(id, playbackState, playWhenReady, isPlaying)
        val sample = sample(id) ?: return
        synchronized(sample) {
            val now = SystemClock.elapsedRealtime()
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            PlaybackProfiler.playerState(id, stateName, playWhenReady, isPlaying, 0L)
            if (playbackState == Player.STATE_BUFFERING && sample.bufferingStartedAtMs < 0L) {
                sample.bufferingStartedAtMs = now
            }
            if (playbackState == Player.STATE_READY && playWhenReady && sample.readyAtMs < 0L) {
                sample.readyAtMs = now
                PreparedTrackRegistry.markPlaying(id)
            }
            Log.i(TAG, "event=player_state trackId=" + id +
                " state=" + stateName + " playWhenReady=" + playWhenReady +
                " isPlaying=" + isPlaying + " elapsedRealtimeMs=" + now)
        }
    }

    fun playerError(trackId: String?, code: Int, codeName: String) {
        val id = trackId ?: return
        StabilityMetrics.playerError()
        PlaybackProfiler.record("player_error", id, "code=$code name=$codeName")
        PlaybackReliabilityLayer.reportPlayerError(id, code, codeName)
        val sample = sample(id) ?: return
        Log.w(TAG, "event=player_error trackId=" + id +
            " code=" + code + " name=" + codeName)
    }

    fun firstAudioOutput(trackId: String?, signal: String) {
        val id = trackId ?: return
        PlaybackCriticalPathAnalyzer.firstAudioFrame(id, signal)
        PlaybackProfiler.firstAudio(id, signal)
        val sample = sample(id) ?: return
        synchronized(sample) {
            if (sample.firstAudioAtMs >= 0L) return
            val now = SystemClock.elapsedRealtime()
            sample.firstAudioAtMs = now
            val total = (now - sample.selectedAtElapsedMs).coerceAtLeast(0L)
            val buffering = if (sample.bufferingStartedAtMs >= 0L && sample.readyAtMs >= sample.bufferingStartedAtMs) {
                sample.readyAtMs - sample.bufferingStartedAtMs
            } else -1L
            val firstAudio = if (sample.readyAtMs >= 0L) (now - sample.readyAtMs).coerceAtLeast(0L) else -1L
            Log.i(
                TAG,
                "STARTUP_BREAKDOWN trackId=" + id +
                    " source=" + sample.source +
                    " selectionOrdinalSinceLaunch=" + sample.selectionOrdinal +
                    " songSelectionTimestampMs=" + sample.selectedAtWallMs +
                    " resolverDurationMs=" + sample.resolverDurationMs +
                    " resolverAttempts=" + sample.resolverAttempts +
                    " serviceStartCallDurationMs=" + sample.serviceStartCallDurationMs +
                    " mediaItemDurationMs=" + sample.mediaItemDurationMs +
                    " queueSize=" + sample.queueSize +
                    " playerSetMediaItemsDurationMs=" + sample.setMediaItemsDurationMs +
                    " prepareDurationMs=" + sample.prepareCallDurationMs +
                    " bufferingDurationMs=" + buffering +
                    " firstAudioDurationMs=" + firstAudio +
                    " totalStartupDurationMs=" + total +
                    " firstAudioSignal=" + signal
            )
            samples.remove(id, sample)
        }
    }

    private fun sample(trackId: String): Sample? =
        if (!BuildConfig.DEBUG || trackId.isBlank()) null else samples[trackId]
}

/** Reuses the resolver-owned caches without changing stream selection or fallback order. */
object ResolvedTrackCache {
    fun peek(trackId: String): StreamInfo? {
        if (trackId.isBlank()) return null
        return (PlaybackRepository.resolvedStream(trackId) ?: LosslessSource.peek(trackId) ?: FastStream.peek(trackId))
            ?.takeIf { it.expiresAtEpochMs <= 0L || it.expiresAtEpochMs > System.currentTimeMillis() }
    }
}

/** Bounded metadata cache; each song is still registered with LosslessSource on conversion. */
object MediaItemCache {
    private data class Entry(val fingerprint: String, val item: MediaItem)
    private const val MAX_ITEMS = 384
    private val items = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_ITEMS
    }

    fun getOrCreate(trackId: String, fingerprint: String, create: () -> MediaItem): MediaItem =
        run {
            var cacheHit = false
            val startedAt = SystemClock.elapsedRealtime()
            val item = synchronized(items) {
                items[trackId]?.takeIf { it.fingerprint == fingerprint }?.let {
                    cacheHit = true
                    return@synchronized it.item
                }
                create().also { items[trackId] = Entry(fingerprint, it) }
            }
            PlaybackCriticalPathAnalyzer.accumulatePhaseDuration(
                trackId,
                "metadata_retrieval_and_media_item_build",
                SystemClock.elapsedRealtime() - startedAt,
                "cacheHit=$cacheHit"
            )
            item
        }

    fun clear(trackId: String) {
        synchronized(items) { items.remove(trackId) }
    }
}

object PlaybackMetrics {
    private const val TAG = "KiloPlaybackMetrics"
    private val selections = ConcurrentHashMap<String, Long>()
    private val starts = ConcurrentHashMap<String, Long>()
    private val resolveHits = java.util.concurrent.atomic.AtomicLong()
    private val resolveMisses = java.util.concurrent.atomic.AtomicLong()
    private val resolverCounts = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val mediaItemCounts = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val prepareCounts = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val loadingEntries = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    fun onTrackSelected(trackId: String, source: String) {
        if (!BuildConfig.DEBUG || trackId.isBlank()) return
        selections[trackId] = SystemClock.elapsedRealtime()
        starts.remove(trackId)
        Log.d(TAG, "trackSelected source=$source id=$trackId")
    }

    fun onResolverStarted(trackId: String, cacheHit: Boolean) {
        if (!BuildConfig.DEBUG || trackId.isBlank()) return
        PlaybackStartupMetrics.resolverStarted(trackId, cacheHit)
        val invocation = resolverCounts.computeIfAbsent(trackId) { _: String -> java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        if (cacheHit) resolveHits.incrementAndGet() else resolveMisses.incrementAndGet()
        val hits = resolveHits.get()
        val misses = resolveMisses.get()
        val total = hits + misses
        val rate = if (total == 0L) 0L else hits * 100L / total
        Log.d(TAG, "resolverRequest id=$trackId invocation=$invocation cacheHit=$cacheHit cacheHitRate=$rate%")
    }

    fun onResolverFinished(trackId: String, startedAt: Long, info: StreamInfo?, failure: String? = null) {
        if (!BuildConfig.DEBUG) return
        val duration = SystemClock.elapsedRealtime() - startedAt
        PlaybackStartupMetrics.resolverFinished(trackId, failure)
        val validationStartedAt = SystemClock.elapsedRealtime()
        val resolvedUri = info?.url?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
        val urlValid = resolvedUri?.scheme?.lowercase() in setOf("http", "https") &&
            !resolvedUri?.host.isNullOrBlank()
        PlaybackCriticalPathAnalyzer.phaseDuration(
            trackId,
            "url_validation",
            SystemClock.elapsedRealtime() - validationStartedAt,
            "valid=$urlValid"
        )
        val result = if (info == null) "miss" else "ready"
        val bitrate = info?.bitrateKbps ?: -1
        val expiry = info?.expiresAtEpochMs ?: -1L
        Log.d(TAG, "resolverFinished id=$trackId durationMs=$duration result=$result bitrateKbps=$bitrate expiresAtMs=$expiry" + " failure=" + failure.orEmpty())
    }

    fun onMediaItemCreated(trackId: String) {
        if (!BuildConfig.DEBUG || trackId.isBlank()) return
        val count = mediaItemCounts.computeIfAbsent(trackId) { _: String -> java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        Log.d(TAG, "mediaItemCreated id=$trackId count=$count")
    }

    fun onPlayerPrepare(trackId: String, source: String) {
        if (!BuildConfig.DEBUG || trackId.isBlank()) return
        val count = prepareCounts.computeIfAbsent(trackId) { _: String -> java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        Log.d(TAG, "playerPrepare id=$trackId source=$source count=$count")
    }

    fun onInitialLoading(trackId: String) {
        if (!BuildConfig.DEBUG || trackId.isBlank()) return
        val count = loadingEntries.computeIfAbsent(trackId) { _: String -> java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        Log.d(TAG, "initialLoading id=$trackId count=$count")
    }

    fun onPlayerReady() {
        if (BuildConfig.DEBUG) Log.i(TAG, "playerReady")
    }

    fun onPlaybackState(trackId: String?, state: Int, isPlaying: Boolean) {
        if (!BuildConfig.DEBUG || trackId.isNullOrBlank()) return
        if (state == Player.STATE_READY && isPlaying) onAudioStarted(trackId, "READY")
    }

    fun onAudioStarted(trackId: String, reason: String) {
        if (!BuildConfig.DEBUG || trackId.isBlank()) return
        val selectedAt = selections[trackId] ?: return
        if (starts.putIfAbsent(trackId, SystemClock.elapsedRealtime()) != null) return
        val latency = SystemClock.elapsedRealtime() - selectedAt
        Log.i(TAG, "audioStarted id=$trackId sourceLatencyMs=$latency state=$reason")
    }

    fun onAndroidAutoCallback(name: String, mediaId: String, uri: String, durationMs: Long, status: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, "androidAuto callback=$name id=$mediaId uri=$uri durationMs=$durationMs status=$status")
    }
}

/** Serializes speculative next-track resolves so prefetch never fans out network work. */
object QueuePreloader {
    private data class Request(val context: Context, val trackId: String)
    private const val MAX_PENDING_TRACKS = 2
    private const val COMPLETED_TTL_MS = 12 * 60 * 1000L
    private val lock = Any()
    private val pending = java.util.ArrayDeque<Request>()
    private val knownIds = ConcurrentHashMap.newKeySet<String>()
    private val completedUntilMs = ConcurrentHashMap<String, Long>()
    private var active = false
    private var activeTrackId: String? = null

    fun enqueue(context: Context, trackIds: List<String>) {
        val appContext = context.applicationContext
        val candidates = trackIds.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_PENDING_TRACKS)
            .toList()
        val candidateIds = candidates.toSet()
        synchronized(lock) {
            val now = System.currentTimeMillis()
            completedUntilMs.entries.removeIf { it.value <= now }
            val removed = pending.filter { it.trackId !in candidateIds }
            pending.removeIf { it.trackId !in candidateIds }
            removed.forEach { knownIds.remove(it.trackId) }
            candidates.forEach { id ->
                if (id == activeTrackId || completedUntilMs[id]?.let { it > now } == true) return@forEach
                if (knownIds.add(id)) pending.addLast(Request(appContext, id))
            }
            while (pending.size > MAX_PENDING_TRACKS) {
                val discarded = pending.removeLast()
                knownIds.remove(discarded.trackId)
            }
        }
        startNext()
    }

    fun forget(trackId: String) {
        knownIds.remove(trackId)
        completedUntilMs.remove(trackId)
        synchronized(lock) { pending.removeIf { it.trackId == trackId } }
    }

    private fun startNext() {
        val request = synchronized(lock) {
            if (active) return
            val next = pending.pollFirst() ?: return
            active = true
            activeTrackId = next.trackId
            next
        }
        PlayerHolder.prefetch(request.context, request.trackId) { succeeded ->
            synchronized(lock) {
                active = false
                activeTrackId = null
                if (succeeded) {
                    knownIds.remove(request.trackId)
                    completedUntilMs[request.trackId] = System.currentTimeMillis() + COMPLETED_TTL_MS
                } else {
                    knownIds.remove(request.trackId)
                }
            }
            startNext()
        }
    }
}

object PlaybackPerformanceLayer {
    private val visiblePreparationRequests = ConcurrentHashMap.newKeySet<String>()
    private val visiblePreparationWorker = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(24),
        { task -> Thread(task, "KiloVisiblePlaybackWarmup").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun selected(trackId: String, source: String) {
        PlaybackCriticalPathAnalyzer.userSelected(trackId, source, preserveRecent = true)
        PlaybackMetrics.onTrackSelected(trackId, source)
        PlaybackProfiler.selected(trackId, source)
        if (BuildConfig.DEBUG) QueueMetrics.selected()
        val ready = ClickToPlayCoordinator.isPrepared(trackId)
        PlaybackProfiler.record("click_to_play", trackId, "preparedTrackHit=$ready")
        if (BuildConfig.DEBUG) {
            PlaybackMetricsManager.record(if (ready) "prepared_track_hit" else "prepared_track_miss", trackId)
        }
    }

    /** Called when a bounded song list is displayed; work is scheduled off the UI thread. */
    @androidx.media3.common.util.UnstableApi
    fun prepareVisible(context: Context, songs: List<SongItemClean>, section: String) {
        val limit = if (section.startsWith("search")) 10 else 20
        val selected = songs.asSequence()
            .filter { it.videoId.isNotBlank() && !it.videoId.startsWith("radio:") }
            .distinctBy { it.videoId.ifBlank { it.id } }
            .take(limit)
            .toList()
        if (selected.isEmpty()) return
        val app = context.applicationContext
        val requestKey = "$section:${selected.joinToString("|") { it.videoId.ifBlank { it.id } }}"
        if (!visiblePreparationRequests.add(requestKey)) return
        try {
            visiblePreparationWorker.execute {
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    val tracks = selected.mapNotNull { song ->
                        runCatching {
                            val item = song.toMediaItem()
                            PreparedPlaybackPlatform.create(
                                item,
                                song.title,
                                song.artist,
                                song.album,
                                song.durationMs,
                                song.coverUrl
                            )
                        }.getOrNull()
                    }
                    when {
                        section.startsWith("search") -> SearchWarmupManager.prepare(app, tracks)
                        section.contains("playlist", ignoreCase = true) -> PlaylistWarmupManager.prepare(app, tracks)
                        section.contains("album", ignoreCase = true) -> AlbumWarmupManager.prepare(app, tracks)
                        section.startsWith("radio", ignoreCase = true) -> RadioWarmupManager.prepare(app, tracks)
                        section.contains("mood", ignoreCase = true) -> MoodWarmupManager.prepare(app, tracks)
                        section.startsWith("android_auto", ignoreCase = true) -> {
                            AndroidAutoWarmupManager.noteCarConnected()
                            ScreenWarmupManager.prepare(app, tracks, section, tracks.size.coerceAtMost(20))
                        }
                        else -> ScreenWarmupManager.prepare(app, tracks, section, tracks.size.coerceAtMost(20))
                    }
                    PlaybackMetricsManager.record(
                        "visible_tracks_prepared",
                        durationMs = SystemClock.elapsedRealtime() - startedAt,
                        details = "section=$section count=${tracks.size} worker=${Thread.currentThread().name}"
                    )
                } finally {
                    visiblePreparationRequests.remove(requestKey)
                }
            }
        } catch (_: RejectedExecutionException) {
            visiblePreparationRequests.remove(requestKey)
            PlaybackMetricsManager.record("visible_prepare_queue_full", details = "section=$section count=${selected.size}")
        }
    }

    fun onPlayerState(context: Context, player: Player, state: Int, isPlaying: Boolean) {
        PreparedPlaybackLayer.observeQueue(player)
        val currentId = player.currentMediaItem?.mediaId ?: return
        PlaybackMetrics.onPlaybackState(currentId, state, isPlaying)
        if (isPlaying) SourceLifecycleManager.transition(currentId, SourceLifecycleState.ACTIVE)
        ContinuityManager.observe(player)
        val bufferMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
        BufferGuard.observe(bufferMs, state == Player.STATE_BUFFERING, AndroidAutoPerformanceMode.enabled(context))
        if (isPlaying) {
            ResolvedTrackCache.peek(currentId)?.let { stream ->
                StreamContinuityCache.warmAhead(context, currentId, player.currentPosition, bufferMs, stream)
            }
        }
        QueueSnapshotCache.capture(player)
        if (AndroidAutoPerformanceMode.enabled(context)) AndroidAutoQueueMirror.capture(player)
        PlaybackReliabilityLayer.prefetchUpcoming(context, player, isPlaying)
    }
}

object PlaybackWarmupManager {
    private const val TAG = "KiloWarmup"
    @Volatile private var processStartedAt = 0L
    private val cacheWarmupStarted = AtomicBoolean(false)
    fun initialize(context: Context) {
        val app = context.applicationContext
        processStartedAt = SystemClock.elapsedRealtime()
        PlaybackStartupMetrics.applicationStarted()
        StartupOrchestrator.beginPhaseA()
        Log.i(TAG, "applicationStarted")
        PreparedPlaybackPlatform.cleanExpired()
        val player = PlayerHolder.get(app)
        runCatching { com.example.kilo.audio.engine.AudioEngine.initialize(app, player) }
            .onFailure { Log.w(TAG, "optional sound profile layer unavailable; original playback remains active", it) }
        PlaybackReliabilityLayer.initialize(app, player)
        runCatching { StreamContinuityCache.attach(app, player) }
        PlaybackMetrics.onPlayerReady()
        val playerItems = player.mediaItemCount
        val readyMs = SystemClock.elapsedRealtime() - processStartedAt
        Log.i(TAG, "playerReadyFromLaunchMs=$readyMs playerItems=$playerItems")
        StartupOrchestrator.playerReady()
        StartupOrchestrator.scheduleDeferredPhases(
            phaseB = { startPhaseB(app, player) },
            phaseC = { startPhaseC(app) }
        )
    }

    fun onMediaSessionReady() {
        if (processStartedAt == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - processStartedAt
        Log.i(TAG, "mediaSessionReadyFromLaunchMs=$elapsed")
        StartupOrchestrator.mediaSessionReady()
    }

    fun onResolverReady() {
        if (processStartedAt == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - processStartedAt
        Log.i(TAG, "resolverReadyFromLaunchMs=$elapsed")
        StartupOrchestrator.resolverReady()
    }

    private fun warmBrowseCache(context: Context) {
        if (!cacheWarmupStarted.compareAndSet(false, true)) return
        Thread({
            runCatching { AutoBrowseCache.read(context, "root") }
                .onFailure { Log.w(TAG, "browse cache warmup failed", it) }
            Log.i(TAG, "browseCacheWarmupComplete")
        }, "kilo-browse-warmup").start()
    }

    /** Phase B: resolver and local caches start after the initial UI/player window. */
    private fun startPhaseB(context: Context, player: Player) {
        val startedAt = SystemClock.elapsedRealtime()
        runCatching { YtRepo.warmUp() }.onFailure { Log.w(TAG, "YouTube resolver warmup skipped", it) }
        runCatching { FastStream.warmUp() }.onFailure { Log.w(TAG, "fast stream warmup skipped", it) }
        runCatching { AudioCache.get(context) }.onFailure { Log.w(TAG, "audio cache warmup skipped", it) }
        runCatching { AutoBrowseDatabase.get(context) }.onFailure { Log.w(TAG, "Android Auto database warmup skipped", it) }
        runCatching {
            val warmupResolver = SourceWarmupResolver { trackId ->
                PlayerHolder.prepareSourceForWarmup(context, trackId)?.let { info ->
                    WarmedSourceMetadata(
                        streamUrl = info.url,
                        requestHeaders = info.requestHeaders,
                        codec = info.codec,
                        bitrateKbps = info.bitrateKbps,
                        durationMs = info.durationMs,
                        sourceExpiresAtEpochMs = info.expiresAtEpochMs
                    )
                }
            }
            SourceRefreshEngine.install(warmupResolver)
            val cacheLookup: (String) -> WarmedSourceMetadata? = { trackId ->
                ResolvedTrackCache.peek(trackId)?.let { info ->
                    WarmedSourceMetadata(
                        streamUrl = info.url,
                        requestHeaders = info.requestHeaders,
                        codec = info.codec,
                        bitrateKbps = info.bitrateKbps,
                        durationMs = info.durationMs,
                        sourceExpiresAtEpochMs = info.expiresAtEpochMs
                    )
                }
            }
            StreamContinuityCache.installSourceLookup { trackId -> ResolvedTrackCache.peek(trackId) }
            PreparationOrchestrator.install(warmupResolver, cacheLookup)
            PlaybackOptimizer.start(context, player, warmupResolver, sourceAlreadyCached = cacheLookup)
        }.onFailure { error ->
            Log.w(TAG, "optional playback optimizer unavailable; existing playback remains active", error)
        }
        Log.i(
            TAG,
            "phaseBWarmupComplete elapsedSinceLaunchMs=${SystemClock.elapsedRealtime() - processStartedAt} " +
                "workDurationMs=${SystemClock.elapsedRealtime() - startedAt}"
        )
    }

    /** Phase C: Android Auto catalog warmup and browse-cache I/O wait until later startup. */
    private fun startPhaseC(context: Context) {
        val startedAt = SystemClock.elapsedRealtime()
        AndroidAutoWarmupManager.initialize(context)
        warmBrowseCache(context)
        Log.i(
            TAG,
            "phaseCWarmupScheduled elapsedSinceLaunchMs=${SystemClock.elapsedRealtime() - processStartedAt} " +
                "workDurationMs=${SystemClock.elapsedRealtime() - startedAt}"
        )
    }
}
