package com.example.kilo

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.kilo.streaming.AudioCache
import com.example.kilo.streaming.FastStream
import com.example.kilo.streaming.StreamInfo
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
        val ordinal = selectionCount.incrementAndGet()
        val sample = Sample(trackId, source, selectedAtElapsedMs, selectedAtWallMs, ordinal)
        samples[trackId] = sample
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
        val sample = sample(trackId) ?: return
        synchronized(sample) {
            sample.resolverStarts.addLast(ResolverAttempt(SystemClock.elapsedRealtime(), cacheHit))
            sample.resolverAttempts += 1
            Log.i(TAG, "event=resolver_start trackId=" + trackId +
                " attempt=" + sample.resolverAttempts + " cacheHit=" + cacheHit)
        }
    }

    fun resolverFinished(trackId: String, failure: String? = null) {
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
        val sample = sample(trackId) ?: return
        synchronized(sample) {
            sample.serviceStartCallDurationMs = durationMs.coerceAtLeast(0L)
            Log.i(TAG, "event=service_start_call trackId=" + trackId +
                " durationMs=" + sample.serviceStartCallDurationMs)
        }
    }

    fun mediaItemsBuilt(trackId: String, durationMs: Long, queueSize: Int) {
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
        val sample = sample(trackId) ?: return
        synchronized(sample) {
            sample.setMediaItemsDurationMs = durationMs.coerceAtLeast(0L)
            Log.i(TAG, "event=player_media_items trackId=" + trackId +
                " operation=" + operation +
                " durationMs=" + sample.setMediaItemsDurationMs)
        }
    }

    fun mediaItemsDelivered(trackId: String, source: String, durationMs: Long) {
        val sample = sample(trackId) ?: return
        synchronized(sample) {
            Log.i(TAG, "event=media_items_delivered trackId=" + trackId +
                " source=" + source + " callbackDurationMs=" + durationMs.coerceAtLeast(0L))
        }
    }

    fun prepareCall(trackId: String, durationMs: Long) {
        val sample = sample(trackId) ?: return
        synchronized(sample) {
            sample.prepareCallDurationMs = durationMs.coerceAtLeast(0L)
            Log.i(TAG, "event=player_prepare trackId=" + trackId +
                " prepareDurationMs=" + sample.prepareCallDurationMs)
        }
    }

    fun playerState(trackId: String?, playbackState: Int, playWhenReady: Boolean, isPlaying: Boolean) {
        val id = trackId ?: return
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
            if (playbackState == Player.STATE_BUFFERING && sample.bufferingStartedAtMs < 0L) {
                sample.bufferingStartedAtMs = now
            }
            if (playbackState == Player.STATE_READY && playWhenReady && sample.readyAtMs < 0L) {
                sample.readyAtMs = now
            }
            Log.i(TAG, "event=player_state trackId=" + id +
                " state=" + stateName + " playWhenReady=" + playWhenReady +
                " isPlaying=" + isPlaying + " elapsedRealtimeMs=" + now)
        }
    }

    fun playerError(trackId: String?, code: Int, codeName: String) {
        val id = trackId ?: return
        val sample = sample(id) ?: return
        Log.w(TAG, "event=player_error trackId=" + id +
            " code=" + code + " name=" + codeName)
    }

    fun firstAudioOutput(trackId: String?, signal: String) {
        val id = trackId ?: return
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
        return (LosslessSource.peek(trackId) ?: FastStream.peek(trackId))
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
        synchronized(items) {
            items[trackId]?.takeIf { it.fingerprint == fingerprint }?.let { return@synchronized it.item }
            create().also { items[trackId] = Entry(fingerprint, it) }
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
    private val lock = Any()
    private val pending = java.util.ArrayDeque<Request>()
    private val knownIds = ConcurrentHashMap.newKeySet<String>()
    private var active = false

    fun enqueue(context: Context, trackIds: List<String>) {
        val appContext = context.applicationContext
        synchronized(lock) {
            trackIds.filter(String::isNotBlank).distinct().forEach { id ->
                if (knownIds.add(id)) pending.addLast(Request(appContext, id))
            }
        }
        startNext()
    }

    fun forget(trackId: String) {
        knownIds.remove(trackId)
        synchronized(lock) { pending.removeIf { it.trackId == trackId } }
    }

    private fun startNext() {
        val request = synchronized(lock) {
            if (active) return
            val next = pending.pollFirst() ?: return
            active = true
            next
        }
        PlayerHolder.prefetch(request.context, request.trackId) {
            synchronized(lock) { active = false }
            startNext()
        }
    }
}

object PlaybackPerformanceLayer {
    fun selected(trackId: String, source: String) = PlaybackMetrics.onTrackSelected(trackId, source)

    fun onPlayerState(context: Context, player: Player, state: Int, isPlaying: Boolean) {
        val currentId = player.currentMediaItem?.mediaId ?: return
        PlaybackMetrics.onPlaybackState(currentId, state, isPlaying)
        if (!isPlaying || currentId.startsWith("radio:")) return
        val count = player.mediaItemCount
        if (count < 2) return
        val ids = (1..minOf(4, count - 1))
            .map { offset -> player.getMediaItemAt((player.currentMediaItemIndex + offset) % count).mediaId }
            .filterNot { it.startsWith("radio:") }
        QueuePreloader.enqueue(context, ids)
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
        Log.i(TAG, "applicationStarted")
        YtRepo.warmUp()
        FastStream.warmUp()
        AudioCache.get(app)
        AutoBrowseDatabase.get(app)
        val player = PlayerHolder.get(app)
        PlaybackMetrics.onPlayerReady()
        val playerItems = player.mediaItemCount
        val readyMs = SystemClock.elapsedRealtime() - processStartedAt
        Log.i(TAG, "playerReadyFromLaunchMs=$readyMs playerItems=$playerItems")
        warmBrowseCache(app)
    }

    fun onMediaSessionReady() {
        if (processStartedAt == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - processStartedAt
        Log.i(TAG, "mediaSessionReadyFromLaunchMs=$elapsed")
    }

    fun onResolverReady() {
        if (processStartedAt == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - processStartedAt
        Log.i(TAG, "resolverReadyFromLaunchMs=$elapsed")
    }

    private fun warmBrowseCache(context: Context) {
        if (!cacheWarmupStarted.compareAndSet(false, true)) return
        Thread({
            runCatching { AutoBrowseCache.read(context, "root") }
                .onFailure { Log.w(TAG, "browse cache warmup failed", it) }
            Log.i(TAG, "browseCacheWarmupComplete")
        }, "kilo-browse-warmup").start()
    }
}
