package com.example.kilo.playbackoptimization

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.app.UiModeManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Small, presentation-safe summary; no player commands or queue mutations live here. */
data class PreparedTrack(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkUri: String,
    val mediaItem: MediaItem? = null
)

enum class PreparedTrackStatus { PREPARING, READY, PLAYING, EXPIRING, REFRESHING, FAILED }

data class PreparedTrackRecord(
    val trackId: String,
    val mediaItem: MediaItem?,
    val artwork: String,
    val durationMs: Long,
    val streamUrl: String?,
    val headers: Map<String, String>,
    val expiryTimestampMs: Long,
    val format: String,
    val bitrateKbps: Int,
    val validationTimeMs: Long,
    val status: PreparedTrackStatus,
    val updatedAtMs: Long
)

/** Bounded prepared-track registry; it never owns or commands the active player. */
object PreparedTrackRegistry {
    private const val MAX_TRACKS = 32
    private const val TTL_MS = 15 * 60 * 1000L
    private val records = object : LinkedHashMap<String, PreparedTrackRecord>(MAX_TRACKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PreparedTrackRecord>?): Boolean =
            size > MAX_TRACKS
    }

    fun register(track: PreparedTrack) = synchronized(records) {
        if (track.mediaId.isBlank()) return@synchronized
        val old = records[track.mediaId]
        val sourceReady = old?.let { sourceIsCurrent(it, System.currentTimeMillis()) } == true
        records[track.mediaId] = PreparedTrackRecord(
            trackId = track.mediaId,
            mediaItem = track.mediaItem ?: old?.mediaItem,
            artwork = track.artworkUri,
            durationMs = track.durationMs,
            streamUrl = old?.streamUrl,
            headers = old?.headers.orEmpty(),
            expiryTimestampMs = old?.expiryTimestampMs ?: 0L,
            format = old?.format.orEmpty(),
            bitrateKbps = old?.bitrateKbps ?: 0,
            validationTimeMs = old?.validationTimeMs ?: 0L,
            status = if (sourceReady) PreparedTrackStatus.READY else old?.status ?: PreparedTrackStatus.PREPARING,
            updatedAtMs = System.currentTimeMillis()
        )
        PreparedTrackCleaner.trimLocked(records)
    }

    fun markPreparing(mediaId: String) = update(mediaId, PreparedTrackStatus.PREPARING)
    fun markPlaying(mediaId: String) = update(mediaId, PreparedTrackStatus.PLAYING)
    fun markExpiring(mediaId: String) = update(mediaId, PreparedTrackStatus.EXPIRING)
    fun markRefreshing(mediaId: String) = update(mediaId, PreparedTrackStatus.REFRESHING)
    fun markFailed(mediaId: String) = update(mediaId, PreparedTrackStatus.FAILED)

    fun markReady(mediaId: String, source: WarmedSourceMetadata?) = synchronized(records) {
        val old = records[mediaId] ?: return@synchronized
        val usable = source?.takeIf { SourceHealthValidator.isUsable(it) }
        records[mediaId] = old.copy(
            streamUrl = usable?.streamUrl,
            headers = usable?.requestHeaders.orEmpty(),
            expiryTimestampMs = usable?.sourceExpiresAtEpochMs ?: 0L,
            format = usable?.codec.orEmpty(),
            bitrateKbps = usable?.bitrateKbps ?: 0,
            validationTimeMs = usable?.validatedAtEpochMs ?: 0L,
            status = if (usable == null) PreparedTrackStatus.FAILED else PreparedTrackStatus.READY,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun get(mediaId: String): PreparedTrackRecord? = synchronized(records) {
        PreparedTrackCleaner.trimLocked(records)
        val record = records[mediaId] ?: return@synchronized null
        if (record.expiryTimestampMs > 0L && record.expiryTimestampMs <= System.currentTimeMillis()) {
            records[mediaId] = record.copy(status = PreparedTrackStatus.EXPIRING)
            SourceLifecycleManager.transition(mediaId, SourceLifecycleState.EXPIRING)
        }
        records[mediaId]
    }

    fun snapshot(): List<PreparedTrackRecord> = synchronized(records) {
        PreparedTrackCleaner.trimLocked(records)
        records.values.toList()
    }

    internal fun cleanExpired() = synchronized(records) { PreparedTrackCleaner.trimLocked(records) }

    private fun update(mediaId: String, status: PreparedTrackStatus) = synchronized(records) {
        records[mediaId]?.let { records[mediaId] = it.copy(status = status, updatedAtMs = System.currentTimeMillis()) }
    }

    private fun sourceIsCurrent(record: PreparedTrackRecord, now: Long): Boolean =
        !record.streamUrl.isNullOrBlank() && (record.expiryTimestampMs <= 0L || record.expiryTimestampMs > now)
}

object PreparedTrackStore {
    fun get(trackId: String): PreparedTrackRecord? = PreparedTrackRegistry.get(trackId)
    fun snapshot(): List<PreparedTrackRecord> = PreparedTrackRegistry.snapshot()
}

object PreparedTrackCleaner {
    private const val TTL_MS = 15 * 60 * 1000L

    fun clean() = PreparedTrackRegistry.cleanExpired()

    internal fun trimLocked(records: LinkedHashMap<String, PreparedTrackRecord>) {
        val now = System.currentTimeMillis()
        records.entries.removeIf { (_, record) ->
            now - record.updatedAtMs > TTL_MS && record.status != PreparedTrackStatus.PLAYING
        }
    }
}

object PreparedTrackFactory {
    fun create(
        mediaItem: MediaItem,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        artworkUri: String
    ): PreparedTrack = PreparedTrack(
        mediaId = mediaItem.mediaId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        artworkUri = artworkUri,
        mediaItem = mediaItem
    )
}

/** Existing resolver fallback stays authoritative; this wrapper validates its prepared result. */
object SourceHealthValidator {
    fun isUsable(source: WarmedSourceMetadata, nowMs: Long = System.currentTimeMillis()): Boolean = source.isValid(nowMs)
}

enum class SourceLifecycleState { UNPREPARED, PREPARING, READY, ACTIVE, EXPIRING, REFRESHING, FAILED }

object SourceLifecycleManager {
    private const val MAX_TRACKS = 64
    private val states = object : LinkedHashMap<String, SourceLifecycleState>(MAX_TRACKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SourceLifecycleState>?): Boolean =
            size > MAX_TRACKS
    }

    @Synchronized fun transition(mediaId: String, state: SourceLifecycleState) {
        if (mediaId.isNotBlank()) states[mediaId] = state
    }

    @Synchronized fun state(mediaId: String): SourceLifecycleState = states[mediaId] ?: SourceLifecycleState.UNPREPARED
}

object SourceFailureTracker {
    private const val MAX_TRACKS = 64
    private val failures = LinkedHashMap<String, Long>(MAX_TRACKS, 0.75f, true)

    @Synchronized fun record(mediaId: String) {
        if (mediaId.isBlank()) return
        failures[mediaId] = (failures[mediaId] ?: 0L) + 1L
        while (failures.size > MAX_TRACKS) failures.remove(failures.keys.first())
    }

    @Synchronized fun count(mediaId: String): Long = failures[mediaId] ?: 0L
}

/** The scheduler is a facade over the existing single-worker source warm pool. */
object PreparedTrackScheduler {
    fun schedule(mediaId: String, resolver: SourceWarmupResolver, retryMiss: Boolean = false) {
        PreparedTrackRegistry.markPreparing(mediaId)
        SourceWarmPool.warm(mediaId, resolver, retryMiss)
    }
}

object SourceWarmPool {
    fun warm(mediaId: String, resolver: SourceWarmupResolver, retryMiss: Boolean = false) {
        SourceWarmupManager.warm(mediaId, resolver, retryMiss)
    }
}

/** Metadata about a source already resolved by KILO's existing resolver. */
data class WarmedSourceMetadata(
    val streamUrl: String,
    val requestHeaders: Map<String, String>,
    val codec: String,
    val bitrateKbps: Int,
    val durationMs: Long,
    val sourceExpiresAtEpochMs: Long,
    val validatedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun isValid(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val scheme = runCatching { java.net.URI(streamUrl).scheme?.lowercase() }.getOrNull()
        return (scheme == "https" || scheme == "http") &&
            runCatching { java.net.URI(streamUrl).host?.isNotBlank() == true }.getOrDefault(false) &&
            (sourceExpiresAtEpochMs <= 0L || sourceExpiresAtEpochMs > nowEpochMs)
    }
}

data class PlayableTrackSnapshot(
    val track: PreparedTrack,
    val availability: String,
    val source: WarmedSourceMetadata?,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long
)

/** The visible-track snapshot is intentionally small and capped independently of the app catalog. */
object PlayableTrackSnapshotCache {
    const val MAX_SNAPSHOTS = 20
    private const val TTL_MS = 10 * 60 * 1000L
    private val snapshots = object : LinkedHashMap<String, PlayableTrackSnapshot>(MAX_SNAPSHOTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlayableTrackSnapshot>?): Boolean =
            size > MAX_SNAPSHOTS
    }

    fun putVisible(track: PreparedTrack) = synchronized(snapshots) {
        if (track.mediaId.isBlank()) return@synchronized
        val now = System.currentTimeMillis()
        snapshots[track.mediaId] = PlayableTrackSnapshot(track, "visible", null, now, now + TTL_MS)
    }

    fun markResolved(mediaId: String, source: WarmedSourceMetadata?) = synchronized(snapshots) {
        val current = snapshots[mediaId] ?: return@synchronized
        val now = System.currentTimeMillis()
        snapshots[mediaId] = current.copy(
            availability = if (source?.isValid(now) == true) "ready" else "unavailable",
            source = source?.takeIf { it.isValid(now) },
            expiresAtEpochMs = minOf(current.expiresAtEpochMs, source?.sourceExpiresAtEpochMs?.takeIf { it > now } ?: current.expiresAtEpochMs)
        )
    }

    fun get(mediaId: String): PlayableTrackSnapshot? = synchronized(snapshots) {
        val item = snapshots[mediaId] ?: return@synchronized null
        if (item.expiresAtEpochMs <= System.currentTimeMillis()) {
            snapshots.remove(mediaId)
            null
        } else item
    }
}

/** Bounded source URL/header snapshots. The authoritative resolver cache remains untouched. */
object StreamPreparationPool {
    const val MAX_PREPARED_SOURCES = 20
    private val sources = object : LinkedHashMap<String, WarmedSourceMetadata>(MAX_PREPARED_SOURCES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WarmedSourceMetadata>?): Boolean =
            size > MAX_PREPARED_SOURCES
    }

    fun put(mediaId: String, source: WarmedSourceMetadata) = synchronized(sources) {
        if (mediaId.isNotBlank() && source.isValid()) sources[mediaId] = source
    }

    fun get(mediaId: String): WarmedSourceMetadata? = synchronized(sources) {
        val source = sources[mediaId] ?: return@synchronized null
        if (!source.isValid()) {
            sources.remove(mediaId)
            null
        } else source
    }

    fun clear(mediaId: String) = synchronized(sources) { sources.remove(mediaId) }
}

fun interface SourceWarmupResolver {
    fun resolve(mediaId: String): WarmedSourceMetadata?
}

/** Reports optimization activity without controlling playback. */
object PlaybackMetricsManager {
    private const val TAG = "KiloPlaybackOptimization"
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    fun record(event: String, mediaId: String = "", durationMs: Long? = null, details: String = "") {
        counts.computeIfAbsent(event) { AtomicLong() }.incrementAndGet()
        Log.d(TAG, buildString {
            append("event=").append(event)
            if (mediaId.isNotBlank()) append(" mediaId=").append(mediaId)
            if (durationMs != null) append(" durationMs=").append(durationMs.coerceAtLeast(0L))
            if (details.isNotBlank()) append(' ').append(details)
        })
    }

    fun report(): Map<String, Long> = counts.mapValues { it.value.get() }
}

/** Session-scoped, bounded metadata cache. */
object MetadataCacheManager {
    private const val CAPACITY = 128
    private const val TTL_MS = 4 * 60 * 60 * 1000L
    private data class Entry(val value: PreparedTrack, val savedAtMs: Long)
    private val values = object : LinkedHashMap<String, Entry>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > CAPACITY
    }

    fun put(track: PreparedTrack) {
        if (track.mediaId.isBlank()) return
        synchronized(values) { values[track.mediaId] = Entry(track, SystemClock.elapsedRealtime()) }
    }

    fun get(mediaId: String): PreparedTrack? = synchronized(values) {
        val entry = values[mediaId] ?: return@synchronized null
        if (SystemClock.elapsedRealtime() - entry.savedAtMs > TTL_MS) {
            values.remove(mediaId)
            null
        } else entry.value
    }

    fun clearSession() = synchronized(values) { values.clear() }

    fun cachedEntryCount(): Int = synchronized(values) { values.size }
}

/** Deduplicates source warmups and keeps only short-lived availability metadata. */
object SourceWarmupManager {
    private const val SUCCESS_TTL_MS = 10 * 60 * 1000L
    private const val MISS_TTL_MS = 30_000L
    private const val MAX_ENTRIES = StreamPreparationPool.MAX_PREPARED_SOURCES
    // Bounded queue covers ten search results, visible mood tracks, and the
    // five-track playback look-ahead without unbounded resolver fan-out.
    private const val MAX_QUEUED_WARMUPS = 24
    private data class Entry(val source: WarmedSourceMetadata?, val expiresAtMs: Long)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val worker = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(MAX_QUEUED_WARMUPS),
        { task -> Thread(task, "kilo-source-warmup").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
    )

    fun isWarm(mediaId: String): Boolean = warmedSource(mediaId) != null

    fun warmedSource(mediaId: String): WarmedSourceMetadata? =
        StreamPreparationPool.get(mediaId) ?: entries[mediaId]
            ?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
            ?.source?.takeIf { it.isValid() }

    fun noteAlreadyResolved(mediaId: String, source: WarmedSourceMetadata? = null) {
        if (mediaId.isBlank()) return
        val now = System.currentTimeMillis()
        val validSource = source?.takeIf { it.isValid(now) }
        if (validSource == null) return
        val sourceExpiry = source?.sourceExpiresAtEpochMs?.takeIf { it > now }
        remember(mediaId, Entry(validSource, minOf(sourceExpiry ?: (now + SUCCESS_TTL_MS), now + SUCCESS_TTL_MS)))
        StreamPreparationPool.put(mediaId, validSource)
        PlayableTrackSnapshotCache.markResolved(mediaId, validSource)
        PreparedTrackRegistry.markReady(mediaId, validSource)
        SourceLifecycleManager.transition(mediaId, SourceLifecycleState.READY)
    }

    fun warm(mediaId: String, resolver: SourceWarmupResolver, retryMiss: Boolean = false) {
        if (mediaId.isBlank() || mediaId.startsWith("radio:")) return
        val now = System.currentTimeMillis()
        val cached = entries[mediaId]
        if (!retryMiss && cached != null && cached.expiresAtMs > now) return
        if (!inFlight.add(mediaId)) return
        PreparedTrackRegistry.markPreparing(mediaId)
        SourceLifecycleManager.transition(mediaId, SourceLifecycleState.PREPARING)
        try {
            worker.execute {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val source = runCatching { resolver.resolve(mediaId) }.getOrNull()
                val finishedAt = System.currentTimeMillis()
                if (source != null && source.isValid(finishedAt)) {
                    val sourceExpiry = source.sourceExpiresAtEpochMs.takeIf { it > finishedAt }
                    val expiry = minOf(sourceExpiry ?: (finishedAt + SUCCESS_TTL_MS), finishedAt + SUCCESS_TTL_MS)
                    remember(mediaId, Entry(source, expiry))
                    StreamPreparationPool.put(mediaId, source)
                    PlayableTrackSnapshotCache.markResolved(mediaId, source)
                    PreparedTrackRegistry.markReady(mediaId, source)
                    SourceLifecycleManager.transition(mediaId, SourceLifecycleState.READY)
                    PlaybackMetricsManager.record(
                        "source_warmed", mediaId, SystemClock.elapsedRealtime() - startedAt,
                        "codec=${source.codec} bitrateKbps=${source.bitrateKbps}"
                    )
                } else {
                    remember(mediaId, Entry(null, finishedAt + MISS_TTL_MS))
                    PlayableTrackSnapshotCache.markResolved(mediaId, null)
                    PreparedTrackRegistry.markFailed(mediaId)
                    SourceLifecycleManager.transition(mediaId, SourceLifecycleState.FAILED)
                    SourceFailureTracker.record(mediaId)
                    PlaybackMetricsManager.record("source_warm_miss", mediaId, SystemClock.elapsedRealtime() - startedAt)
                }
            } catch (error: Exception) {
                remember(mediaId, Entry(null, System.currentTimeMillis() + MISS_TTL_MS))
                PlayableTrackSnapshotCache.markResolved(mediaId, null)
                PreparedTrackRegistry.markFailed(mediaId)
                SourceLifecycleManager.transition(mediaId, SourceLifecycleState.FAILED)
                SourceFailureTracker.record(mediaId)
                PlaybackMetricsManager.record("source_warm_error", mediaId, details = error.javaClass.simpleName)
            } finally {
                inFlight.remove(mediaId)
            }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            inFlight.remove(mediaId)
            SourceLifecycleManager.transition(mediaId, SourceLifecycleState.UNPREPARED)
            PlaybackMetricsManager.record("source_warm_queue_full", mediaId)
        }
    }

    private fun remember(mediaId: String, entry: Entry) {
        entries[mediaId] = entry
        if (entries.size > MAX_ENTRIES) {
            val excess = entries.size - MAX_ENTRIES
            entries.entries.sortedBy { it.value.expiresAtMs }.take(excess).forEach { oldest ->
                entries.remove(oldest.key, oldest.value)
                StreamPreparationPool.clear(oldest.key)
            }
        }
    }
}

/** Read-only look-ahead. At most the next five queued media items are inspected. */
object QueuePredictionManager {
    // Keep speculative preparation close to the active queue: it reduces memory and
    // network contention while still giving the next tracks time to warm.
    const val MAX_LOOKAHEAD = 5

    fun nextTracks(player: Player, limit: Int = MAX_LOOKAHEAD): List<PreparedTrack> {
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        if (count <= 1 || current !in 0 until count || player.repeatMode == Player.REPEAT_MODE_ONE) return emptyList()
        val allowed = minOf(limit.coerceAtLeast(0), MAX_LOOKAHEAD, count - 1)
        if (allowed == 0) return emptyList()
        val wrap = player.repeatMode == Player.REPEAT_MODE_ALL
        val result = ArrayList<PreparedTrack>(allowed)
        for (offset in 1..allowed) {
            var index = current + offset
            if (index >= count) {
                if (!wrap) break
                index %= count
            }
            val item = runCatching { player.getMediaItemAt(index) }.getOrNull() ?: continue
            val track = item.toPreparedTrack() ?: continue
            if (!track.mediaId.startsWith("radio:") && result.none { it.mediaId == track.mediaId }) result += track
        }
        return result
    }
}

/** artwork/source preparation is asynchronous and capped to the bounded look-ahead. */
object PlaybackPreparationManager {
    private const val MAX_VISIBLE_SOURCE_WARMUPS = 8

    fun prepare(
        context: Context,
        tracks: List<PreparedTrack>,
        sourceResolver: SourceWarmupResolver,
        sourceAlreadyCached: (String) -> WarmedSourceMetadata?,
        retrySourceMiss: Boolean = false,
        warmSources: Boolean = true
    ) {
        val app = context.applicationContext
        val artworkSize = AndroidAutoOptimizer.artworkMaxPixels(app)
        tracks.asSequence().filter { it.mediaId.isNotBlank() && !it.mediaId.startsWith("radio:") }
            .distinctBy { it.mediaId }
            .take(QueuePredictionManager.MAX_LOOKAHEAD)
            .forEach { track ->
                PreparedTrackRegistry.register(track)
                MetadataCacheManager.put(track)
                if (track.artworkUri.isNotBlank()) ArtworkWarmupManager.warm(app, track.artworkUri, artworkSize)
                val cachedSource = runCatching { sourceAlreadyCached(track.mediaId) }.getOrNull()
                if (cachedSource != null) {
                    SourceWarmupManager.noteAlreadyResolved(track.mediaId, cachedSource)
                    PlaybackMetricsManager.record("source_cache_hit", track.mediaId)
                } else if (warmSources) {
                    PreparedTrackScheduler.schedule(track.mediaId, sourceResolver, retryMiss = retrySourceMiss)
                }
            }
    }

    /** Prepare only the first visible results, never an entire catalog or playlist. */
    fun prepareVisible(
        context: Context,
        tracks: List<PreparedTrack>,
        sourceResolver: SourceWarmupResolver,
        sourceAlreadyCached: (String) -> WarmedSourceMetadata?,
        warmSources: Boolean = true
    ) {
        val app = context.applicationContext
        val artworkSize = AndroidAutoOptimizer.artworkMaxPixels(app)
        var sourceWarmupsScheduled = 0
        tracks.asSequence()
            .filter { it.mediaId.isNotBlank() && !it.mediaId.startsWith("radio:") }
            .distinctBy { it.mediaId }
            .take(PlayableTrackSnapshotCache.MAX_SNAPSHOTS)
            .forEach { track ->
                PreparedTrackRegistry.register(track)
                MetadataCacheManager.put(track)
                PlayableTrackSnapshotCache.putVisible(track)
                if (track.artworkUri.isNotBlank()) ArtworkWarmupManager.warm(app, track.artworkUri, artworkSize)
                val cached = runCatching { sourceAlreadyCached(track.mediaId) }.getOrNull()
                if (cached != null) {
                    SourceWarmupManager.noteAlreadyResolved(track.mediaId, cached)
                } else if (warmSources && sourceWarmupsScheduled < MAX_VISIBLE_SOURCE_WARMUPS) {
                    // Warm a small, bounded set of visible choices so a tap can use a
                    // ready source. A catalog screen must never resolve every row.
                    sourceWarmupsScheduled++
                    PreparedTrackScheduler.schedule(track.mediaId, sourceResolver)
                }
            }
    }

    fun rememberCurrent(context: Context, item: MediaItem?) {
        val track = item?.toPreparedTrack() ?: return
        MetadataCacheManager.put(track)
        if (track.artworkUri.isNotBlank()) {
            ArtworkWarmupManager.warm(context.applicationContext, track.artworkUri, AndroidAutoOptimizer.artworkMaxPixels(context))
        }
    }
}

/** Schedules look-ahead preparation at transition, 30%, and 70% without touching the queue. */
class TrackTransitionManager {
    private var activeTrackId = ""
    private var preparedBefore60Percent = false
    private var warmedBefore80Percent = false
    private var validatedBefore90Percent = false

    fun tracksToPrepare(currentId: String, positionMs: Long, durationMs: Long, upcoming: List<PreparedTrack>): Pair<List<PreparedTrack>, Boolean> {
        if (currentId.isBlank()) return emptyList<PreparedTrack>() to false
        if (currentId != activeTrackId) {
            activeTrackId = currentId
            preparedBefore60Percent = false
            warmedBefore80Percent = false
            validatedBefore90Percent = false
            return upcoming.take(QueuePredictionManager.MAX_LOOKAHEAD) to false
        }
        if (durationMs <= 0L) return emptyList<PreparedTrack>() to false
        val progress = positionMs.toDouble() / durationMs.toDouble()
        if (progress >= 0.90 && !validatedBefore90Percent) {
            validatedBefore90Percent = true
            return upcoming.take(1) to true
        }
        if (progress >= 0.80 && !warmedBefore80Percent) {
            warmedBefore80Percent = true
            return upcoming.take(1) to false
        }
        if (progress >= 0.60 && !preparedBefore60Percent) {
            preparedBefore60Percent = true
            return upcoming.take(2) to false
        }
        return emptyList<PreparedTrack>() to false
    }
}

/** Observer only: records load errors, buffering, and transitions; never issues player commands. */
object PlaybackHealthMonitor {
    fun createListener(): Player.Listener = object : Player.Listener {
        private var bufferingStartedAtMs = -1L

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    bufferingStartedAtMs = SystemClock.elapsedRealtime()
                    PlaybackMetricsManager.record("buffering_started")
                }
                Player.STATE_READY -> {
                    val started = bufferingStartedAtMs
                    if (started >= 0L) {
                        PlaybackMetricsManager.record("buffering_recovered", durationMs = SystemClock.elapsedRealtime() - started)
                        bufferingStartedAtMs = -1L
                    }
                }
                Player.STATE_ENDED -> PlaybackMetricsManager.record("track_ended")
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            PlaybackMetricsManager.record("track_transition", mediaItem?.mediaId.orEmpty(), details = "reason=$reason")
        }

        override fun onPlayerError(error: PlaybackException) {
            PlaybackMetricsManager.record("player_error", details = "code=${error.errorCode} name=${error.errorCodeName}")
        }
    }
}

/** Applies smaller bounded preparation while Android Auto is actively browsing. */
object AndroidAutoOptimizer {
    private const val ACTIVITY_TTL_MS = 120_000L
    @Volatile private var lastCarActivityMs = 0L
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun noteCarActivity() {
        lastCarActivityMs = SystemClock.elapsedRealtime()
    }

    fun isActive(context: Context): Boolean {
        if (SystemClock.elapsedRealtime() - lastCarActivityMs < ACTIVITY_TTL_MS) return true
        val mode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return runCatching { mode?.currentModeType == Configuration.UI_MODE_TYPE_CAR }.getOrDefault(false)
    }

    fun artworkMaxPixels(context: Context): Int = if (isActive(context)) 256 else 512

    /** Schedules browse artwork work without making an Android Auto callback wait. */
    fun warmArtwork(artworkUri: String) {
        val context = appContext ?: return
        ArtworkWarmupManager.warm(context, artworkUri, maxPixels = 256)
    }

    fun shouldRunNonCriticalWork(context: Context): Boolean = !isActive(context)
}

/** Removable observer/preparation wrapper around an already-created shared player. */
object PlaybackOptimizer {
    private const val TAG = "KiloPlaybackOptimizer"
    private const val TICK_MS = 2_500L
    private val lock = Any()
    @Volatile private var enabled = true
    private var attachedPlayer: Player? = null
    private var handler: Handler? = null
    private var tick: Runnable? = null
    private var healthListener: Player.Listener? = null
    private var transitionListener: Player.Listener? = null
    private var analyticsListener: AnalyticsListener? = null
    private var transitionManager = TrackTransitionManager()
    @Volatile private var visibleSourceResolver: SourceWarmupResolver? = null
    @Volatile private var visibleCacheLookup: ((String) -> WarmedSourceMetadata?)? = null

    fun start(
        context: Context,
        player: Player,
        sourceResolver: SourceWarmupResolver,
        sourceAlreadyCached: (String) -> WarmedSourceMetadata?
    ) {
        if (!enabled) return
        val app = context.applicationContext
        val playerHandler = Handler(player.applicationLooper)
        synchronized(lock) {
            if (attachedPlayer === player) return
            detachLocked()
            attachedPlayer = player
            handler = playerHandler
            transitionManager = TrackTransitionManager()
            visibleSourceResolver = sourceResolver
            visibleCacheLookup = sourceAlreadyCached
        }

        val health = PlaybackHealthMonitor.createListener()
        val observer = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                runSafely(player, app, sourceResolver, sourceAlreadyCached)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                PlaybackCriticalPathAnalyzer.playerState(
                    player.currentMediaItem?.mediaId,
                    playbackState,
                    player.playWhenReady,
                    player.isPlaying
                )
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                PlaybackCriticalPathAnalyzer.playWhenReadyChanged(
                    player.currentMediaItem?.mediaId,
                    playWhenReady,
                    reason,
                    player.playbackState,
                    player.playbackSuppressionReason,
                    player.currentPosition,
                    (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
                )
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                PlaybackCriticalPathAnalyzer.suppressionChanged(
                    player.currentMediaItem?.mediaId,
                    playbackSuppressionReason,
                    player.playWhenReady,
                    player.isPlaying,
                    player.currentPosition
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                PlaybackCriticalPathAnalyzer.playerError(
                    player.currentMediaItem?.mediaId,
                    error.errorCode,
                    error.errorCodeName,
                    player.currentPosition,
                    player.playbackState
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) runSafely(player, app, sourceResolver, sourceAlreadyCached)
            }
        }
        synchronized(lock) {
            healthListener = health
            transitionListener = observer
        }
        val analytics = if (player is ExoPlayer) {
            object : AnalyticsListener {
                override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                    PlaybackCriticalPathAnalyzer.mediaSourceLoadStarted(
                        player.currentMediaItem?.mediaId,
                        mediaLoadData.dataType,
                        mediaLoadData.trackType
                    )
                }

                override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                    PlaybackCriticalPathAnalyzer.firstAudioFrame(player.currentMediaItem?.mediaId)
                }
            }
        } else null
        synchronized(lock) { analyticsListener = analytics }
        playerHandler.post {
            if (!enabled || attachedPlayer !== player) return@post
            runCatching {
                player.addListener(health)
                player.addListener(observer)
                if (player is ExoPlayer && analytics != null) player.addAnalyticsListener(analytics)
                PlaybackMetricsManager.record("optimizer_attached")
                runSafely(player, app, sourceResolver, sourceAlreadyCached)
                val poll = object : Runnable {
                    override fun run() {
                        if (!enabled || attachedPlayer !== player) return
                        runSafely(player, app, sourceResolver, sourceAlreadyCached)
                        playerHandler.postDelayed(this, TICK_MS)
                    }
                }
                synchronized(lock) { tick = poll }
                playerHandler.postDelayed(poll, TICK_MS)
            }.onFailure { disable(it) }
        }
    }

    fun prepareVisible(context: Context, tracks: List<PreparedTrack>, warmSources: Boolean = true) {
        val resolver = visibleSourceResolver ?: return
        val cacheLookup = visibleCacheLookup ?: return
        runCatching { PlaybackPreparationManager.prepareVisible(context, tracks, resolver, cacheLookup, warmSources) }
            .onFailure { PlaybackMetricsManager.record("visible_prepare_error", details = it.javaClass.simpleName) }
    }

    /** Error observer hook: refreshes a stale candidate on the warm worker, without touching the active player. */
    fun refreshSourceInBackground(mediaId: String) {
        val resolver = visibleSourceResolver ?: return
        if (mediaId.isBlank()) return
        SourceWarmPool.warm(mediaId, resolver, retryMiss = true)
    }

    private fun runSafely(
        player: Player,
        context: Context,
        sourceResolver: SourceWarmupResolver,
        sourceAlreadyCached: (String) -> WarmedSourceMetadata?
    ) {
        if (!enabled) return
        runCatching {
            val current = player.currentMediaItem ?: return
            val currentTrack = current.toPreparedTrack() ?: return
            PreparedTrackRegistry.register(currentTrack)
            PreparedTrackRegistry.markPlaying(currentTrack.mediaId)
            SourceLifecycleManager.transition(currentTrack.mediaId, SourceLifecycleState.ACTIVE)
            PlaybackPreparationManager.rememberCurrent(context, current)
            val upcoming = QueuePredictionManager.nextTracks(player)
            val plan = transitionManager.tracksToPrepare(currentTrack.mediaId, player.currentPosition, player.duration, upcoming)
            if (plan.first.isNotEmpty()) {
                PlaybackPreparationManager.prepare(
                    context,
                    plan.first,
                    sourceResolver,
                    sourceAlreadyCached,
                    retrySourceMiss = plan.second,
                    warmSources = true
                )
                PlaybackMetricsManager.record(
                    "tracks_prepared",
                    currentTrack.mediaId,
                    details = "count=${plan.first.size} retry=${plan.second} car=${AndroidAutoOptimizer.isActive(context)}"
                )
            }
        }.onFailure { disable(it) }
    }

    /** Stops only this optional observer; it never pauses, releases, or seeks the player. */
    fun disable(cause: Throwable? = null) {
        enabled = false
        if (cause != null) Log.w(TAG, "optimization disabled; original playback remains active", cause)
        val player: Player?
        val playerHandler: Handler?
        synchronized(lock) {
            player = attachedPlayer
            playerHandler = handler
        }
        playerHandler?.post {
            runCatching {
                healthListener?.let { player?.removeListener(it) }
                transitionListener?.let { player?.removeListener(it) }
                if (player is ExoPlayer) analyticsListener?.let(player::removeAnalyticsListener)
                tick?.let { playerHandler.removeCallbacks(it) }
            }
            synchronized(lock) { detachLocked() }
        }
    }

    /** Test/debug re-enable is explicit; normal app startup does not toggle it. */
    fun enable() {
        enabled = true
    }

    private fun detachLocked() {
        tick?.let { handler?.removeCallbacks(it) }
        attachedPlayer = null
        handler = null
        tick = null
        healthListener = null
        transitionListener = null
        analyticsListener = null
        visibleSourceResolver = null
        visibleCacheLookup = null
    }
}

private fun MediaItem.toPreparedTrack(): PreparedTrack? {
    val id = mediaId.trim().ifBlank { return null }
    val metadata = mediaMetadata
    return PreparedTrack(
        mediaId = id,
        title = metadata.title?.toString().orEmpty(),
        artist = metadata.artist?.toString().orEmpty(),
        album = metadata.albumTitle?.toString().orEmpty(),
        durationMs = metadata.durationMs?.takeIf { it > 0L } ?: 0L,
        artworkUri = metadata.artworkUri?.toString().orEmpty()
    )
}
