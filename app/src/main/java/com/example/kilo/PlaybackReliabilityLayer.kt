package com.example.kilo

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import com.example.kilo.buffermanager.AdaptiveBufferManager
import com.example.kilo.networkmonitor.NetworkHealthMonitor
import com.example.kilo.networkmonitor.NetworkHealthSnapshot
import com.example.kilo.playbackcache.AudioSegmentCache
import com.example.kilo.playbackcache.PlaybackCacheMetrics
import com.example.kilo.playbackrecovery.PlaybackDiagnostics
import com.example.kilo.playbackrecovery.PlaybackRecoveryObserver
import com.example.kilo.sourceprefetch.SourcePrefetchManager
import com.example.kilo.streaming.AudioCache
import com.example.kilo.playback.cache.DiskSourceCache
import java.util.concurrent.Executors

/**
 * A wrapper around KILO's current Media3 path. Existing caches, queue prefetch and retry
 * handling remain authoritative; this layer observes health and tunes speculative look-ahead.
 */
object PlaybackReliabilityLayer {
    private const val TAG = "KiloPlaybackReliability"
    private val lock = Any()
    private val cacheInspector = Executors.newSingleThreadExecutor { task ->
        Thread(task, "kilo-playback-cache-inspector").apply { isDaemon = true }
    }
    @Volatile private var applicationContext: Context? = null
    @Volatile private var attachedPlayer: Player? = null
    private var recoveryObserver: PlaybackRecoveryObserver? = null
    private var cacheObserver: Player.Listener? = null
    private var stabilityTracer: PlaybackStabilityTracer? = null
    private val networkListener: (NetworkHealthSnapshot) -> Unit = { snapshot ->
        PlaybackDiagnostics.record(
            "network", details = "transport=${snapshot.transport} grade=${snapshot.grade} " +
                "downstreamKbps=${snapshot.downstreamKbps} validated=${snapshot.validated}"
        )
    }

    fun initialize(context: Context, player: Player) {
        val app = context.applicationContext
        applicationContext = app
        NetworkHealthMonitor.start(app)
        NetworkHealthMonitor.addListener(networkListener)
        synchronized(lock) {
            if (attachedPlayer === player) return
            attachedPlayer?.let { previous ->
                recoveryObserver?.let(previous::removeListener)
                cacheObserver?.let(previous::removeListener)
                stabilityTracer?.let { tracer ->
                    tracer.detach()
                    previous.removeListener(tracer)
                    (previous as? ExoPlayer)?.removeAnalyticsListener(tracer)
                }
            }
            val observer = PlaybackRecoveryObserver { id -> PlayerHolder.isRetrying(id) }
            observer.attachTo(player)
            player.addListener(observer)
            val tracer = PlaybackStabilityTracer(app).also { it.attach(player) }
            player.addListener(tracer)
            (player as? ExoPlayer)?.addAnalyticsListener(tracer)
            val newCacheObserver = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    onTrackTransition(mediaItem)
                }
            }
            player.addListener(newCacheObserver)
            recoveryObserver = observer
            stabilityTracer = tracer
            cacheObserver = newCacheObserver
            attachedPlayer = player
        }
        player.currentMediaItem?.let(::inspectTrackCache)
        Log.i(TAG, "wrapperReady; active player and existing recovery path preserved")
    }

    /** Called from the existing performance wrapper; it does not mutate the queue. */
    fun prefetchUpcoming(context: Context, player: Player, isPlaying: Boolean) {
        val currentId = player.currentMediaItem?.mediaId ?: return
        if (!isPlaying || currentId.startsWith("radio:")) return
        val plan = AdaptiveBufferManager.plan()
        val lookAhead = minOf(plan.prefetchAhead, 2)
        val upcoming = SourcePrefetchManager.upcomingIds(player, lookAhead)
        if (upcoming.isEmpty()) return
        PlaybackDiagnostics.record(
            "prefetch_scheduled", currentId,
            "count=${upcoming.size} network=${plan.networkGrade} recentUnderrun=${plan.recentUnderrun}"
        )
        QueuePreloader.enqueue(context.applicationContext, upcoming)
    }

    fun onTrackTransition(item: MediaItem?) {
        item?.let(::inspectTrackCache)
    }

    /** Records error telemetry only; PlayerHolder's existing listener owns recovery. */
    fun reportPlayerError(mediaId: String, code: Int, name: String) {
        stabilityTracer?.reportErrorToDiagnostics(mediaId, code, name)
            ?: PlaybackDiagnostics.record(
                "player_error_observed",
                mediaId,
                "code=$code name=$name recoveryOwner=PlayerHolder"
            )
    }

    fun diagnosticsReport(): String = PlaybackDiagnostics.exportReport() + "\n" + PlaybackPerformanceDashboard.report()

    fun activeQueueItemCount(): Int = attachedPlayer?.mediaItemCount ?: 0

    @OptIn(UnstableApi::class)
    private fun inspectTrackCache(item: MediaItem) {
        val id = item.mediaId
        val context = applicationContext ?: return
        if (id.isBlank() || id.startsWith("radio:")) return
        cacheInspector.execute {
            runCatching {
                val resolved = ResolvedTrackCache.peek(id)
                PlaybackCacheMetrics.recordResolvedUrl(id, resolved != null)
                val segmentBytes = resolved?.let { info ->
                    val cache = AudioCache.get(context)
                    AudioSegmentCache.cachedBytes(cache, AudioCache.keyFor(id, info.url))
                } ?: 0L
                DiskSourceCache.recordAudioCoverage(id, segmentBytes)
                PlaybackCacheMetrics.recordSegmentCoverage(id, segmentBytes)
                PlaybackDiagnostics.record(
                    "cache_snapshot", id,
                    "resolvedUrl=${resolved != null} cachedInitialBytes=$segmentBytes"
                )
            }.onFailure { error ->
                Log.d(TAG, "cache inspection skipped id=$id", error)
            }
        }
    }
}
