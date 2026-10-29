package com.example.kilo.playback.stability

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.common.Player
import com.example.kilo.observability.PlaybackProfiler
import com.example.kilo.playbackcache.AudioSegmentCache
import com.example.kilo.streaming.AudioCache
import com.example.kilo.streaming.StreamInfo
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import android.os.Handler

/** Opportunistically keeps a short range ahead of the playhead in KILO's existing audio cache. */
object StreamContinuityCache {
    private const val MIN_BUFFERED_MS = 6_000L
    private const val PREFETCH_AHEAD_MS = 18_000L
    private const val PREFETCH_INTERVAL_MS = 8_000L
    private const val PLAYER_POLL_INTERVAL_MS = 4_000L
    private const val MAX_PREFETCH_BYTES = 512L * 1024L
    private const val MAX_SCAN_BYTES = 32L * 1024L * 1024L
    private const val EXPIRY_REFRESH_WINDOW_MS = 30_000L

    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastScheduledAt = ConcurrentHashMap<String, Long>()
    private val lastRefreshAt = ConcurrentHashMap<String, Long>()
    private val attachmentLock = Any()
    @Volatile private var attachedPlayer: Player? = null
    @Volatile private var playerHandler: Handler? = null
    @Volatile private var playerPoll: Runnable? = null
    @Volatile private var sourceLookup: ((String) -> StreamInfo?)? = null
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(2),
        { task -> Thread(task, "kilo-stream-continuity").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun installSourceLookup(lookup: (String) -> StreamInfo?) {
        sourceLookup = lookup
    }

    /** Periodically observes the existing player on its application looper; it never issues player commands. */
    fun attach(context: Context, player: Player) {
        synchronized(attachmentLock) {
            if (attachedPlayer === player) return
            playerPoll?.let { playerHandler?.removeCallbacks(it) }
            val app = context.applicationContext
            val handler = Handler(player.applicationLooper)
            val poll = object : Runnable {
                override fun run() {
                    if (attachedPlayer !== player) return
                    runCatching {
                        if (player.isPlaying) {
                            val id = player.currentMediaItem?.mediaId.orEmpty()
                            sourceLookup?.invoke(id)?.let { stream ->
                                warmAhead(
                                    app,
                                    id,
                                    player.currentPosition,
                                    (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L),
                                    stream
                                )
                            }
                        }
                    }.onFailure { error ->
                        PlaybackProfiler.record("continuity_observer_failed", detail = error.javaClass.simpleName)
                    }
                    handler.postDelayed(this, PLAYER_POLL_INTERVAL_MS)
                }
            }
            attachedPlayer = player
            playerHandler = handler
            playerPoll = poll
            handler.post(poll)
        }
    }

    fun warmAhead(
        context: Context,
        trackId: String,
        positionMs: Long,
        bufferedMs: Long,
        source: StreamInfo
    ) {
        if (trackId.isBlank() || trackId.startsWith("radio:") || bufferedMs < MIN_BUFFERED_MS) return
        val now = System.currentTimeMillis()
        if (source.expiresAtEpochMs > 0L && source.expiresAtEpochMs - now <= EXPIRY_REFRESH_WINDOW_MS) {
            var shouldRefresh = false
            lastRefreshAt.compute(trackId) { _, previous ->
                if (previous == null || now - previous >= EXPIRY_REFRESH_WINDOW_MS) {
                    shouldRefresh = true
                    now
                } else previous
            }
            if (shouldRefresh) RecoveryCoordinator.refreshInBackground(trackId, "source_near_expiry")
            return
        }
        val previous = lastScheduledAt[trackId] ?: 0L
        if (now - previous < PREFETCH_INTERVAL_MS || !inFlight.add(trackId)) return
        lastScheduledAt[trackId] = now
        val app = context.applicationContext
        try {
            worker.execute {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                try {
                    val bitrateKbps = source.bitrateKbps.takeIf { it > 0 } ?: 160
                    val playheadByte = safeByteOffset(positionMs, bitrateKbps)
                    val aheadBytes = (bitrateKbps.toLong() * PREFETCH_AHEAD_MS / 8L)
                        .coerceIn(64L * 1024L, MAX_PREFETCH_BYTES)
                    val targetEnd = playheadByte + aheadBytes
                    val cache = AudioCache.get(app)
                    val key = AudioCache.keyFor(trackId, source.url)
                    val contiguousEnd = AudioSegmentCache.cachedBytes(cache, key, MAX_SCAN_BYTES)
                    val start = maxOf(playheadByte, contiguousEnd)
                    val length = (targetEnd - start).coerceAtMost(MAX_PREFETCH_BYTES)
                    if (length < 64L * 1024L) return@execute

                    val host = Uri.parse(source.url).host.orEmpty()
                    val headers = if (host.endsWith("youtube.com") || host.endsWith("googlevideo.com")) {
                        source.requestHeaders + mapOf(
                            "Referer" to "https://www.youtube.com/",
                            "Origin" to "https://www.youtube.com",
                            "Accept-Encoding" to "identity"
                        )
                    } else source.requestHeaders
                    val spec = DataSpec.Builder()
                        .setUri(Uri.parse(source.url))
                        .setKey(key)
                        .setPosition(start)
                        .setLength(length)
                        .build()
                    val dataSource = AudioCache.dataSourceFactory(app, source.userAgent, headers).createDataSource()
                    CacheWriter(dataSource, spec, null, null).cache()
                    PlaybackProfiler.record(
                        "continuity_cache_warmed",
                        trackId,
                        "startByte=$start bytes=$length bufferedMs=$bufferedMs " +
                            "durationMs=${android.os.SystemClock.elapsedRealtime() - startedAt}"
                    )
                } catch (error: Exception) {
                    PlaybackProfiler.record("continuity_cache_warm_failed", trackId, error.javaClass.simpleName)
                } finally {
                    inFlight.remove(trackId)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            inFlight.remove(trackId)
        }
    }

    private fun safeByteOffset(positionMs: Long, bitrateKbps: Int): Long =
        ((positionMs.coerceAtLeast(0L) * bitrateKbps.coerceAtLeast(1).toLong()) / 8L)
            .coerceAtMost(MAX_SCAN_BYTES)
}
