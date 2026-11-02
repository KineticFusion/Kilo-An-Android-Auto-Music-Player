package com.example.kilo.playback.v5

import android.content.Context
import android.util.Log
import com.example.kilo.PlayerHolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Refreshes signed stream leases in the background without touching the active Media3 item. */
object StreamLeaseRefreshCoordinator {
    private const val TAG = "KiloStreamLease"
    private const val FAILED_REFRESH_RETRY_MS = 20_000L
    private const val ACTIVE_STREAM_REFRESH_MS = 32_000L
    private const val FRESH_REPLACEMENT_TTL_MS = 45_000L

    private val executor = ScheduledThreadPoolExecutor(2) { task ->
        Thread(task, "kilo-stream-lease-refresh").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val scheduled = ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()
    private val freshReplacementAt = ConcurrentHashMap<String, Long>()
    private val activeLock = Any()
    @Volatile private var activeMediaId: String? = null
    private var activeRefresh: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var appContext: Context? = null
    private val leaseListener: (StreamLeaseManager.Lease) -> Unit = ::schedule

    fun install(context: Context) {
        appContext = context.applicationContext
        StreamLeaseManager.addListener(leaseListener)
        StreamLeaseManager.all().forEach(::schedule)
    }

    /** Refreshes only the currently playing source when the provider omits URL expiry metadata. */
    fun observePlayback(mediaId: String, playWhenReady: Boolean) {
        val id = mediaId.trim()
        synchronized(activeLock) {
            if (!playWhenReady || id.isBlank() || id.startsWith("radio:")) {
                activeRefresh?.cancel(false)
                activeRefresh = null
                activeMediaId = null
                return
            }
            if (activeMediaId != id) {
                activeRefresh?.cancel(false)
                activeRefresh = null
                activeMediaId = id
            }
            if (activeRefresh == null) scheduleActiveRefreshLocked(id)
        }
    }

    /** True only when a background refresh produced a replacement after the current source began. */
    fun hasFreshReplacement(mediaId: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        freshReplacementAt[mediaId]?.let { nowMs >= it && nowMs - it <= FRESH_REPLACEMENT_TTL_MS } == true

    private fun scheduleActiveRefreshLocked(mediaId: String) {
        activeRefresh = executor.schedule(activeRefreshTask@{
            synchronized(activeLock) {
                activeRefresh = null
                if (activeMediaId != mediaId) return@activeRefreshTask
                // Keep refreshing only this active item. Background refresh resolves a new
                // source but leaves the current Media3 item and audio path untouched.
                scheduleActiveRefreshLocked(mediaId)
            }
            Log.i(TAG, "activeSourceRefreshDue id=$mediaId elapsedMs=$ACTIVE_STREAM_REFRESH_MS")
            refreshNow(mediaId)
        }, ACTIVE_STREAM_REFRESH_MS, TimeUnit.MILLISECONDS)
    }

    /** Schedules work only after the lease's safety window begins. */
    fun schedule(lease: StreamLeaseManager.Lease) {
        if (lease.mediaId.isBlank()) return
        val delay = (lease.refreshAfterMs - System.currentTimeMillis()).coerceAtLeast(0L)
        scheduled.remove(lease.mediaId)?.cancel(false)
        scheduled[lease.mediaId] = executor.schedule({
            scheduled.remove(lease.mediaId)
            refreshNow(lease.mediaId)
        }, delay, TimeUnit.MILLISECONDS)
    }

    /**
     * A rejected HTTP stream has already had its lease invalidated. Re-resolve it in the
     * background while Media3 continues consuming buffered audio, so recovery does not
     * have to wait until the active buffer is exhausted.
     */
    fun refreshRejectedSource(mediaId: String) {
        refreshNow(mediaId, allowMissingLease = true)
    }

    /** Queues a refresh; the old valid lease remains available until a replacement is stored. */
    fun refreshNow(mediaId: String) = refreshNow(mediaId, allowMissingLease = false)

    private fun refreshNow(mediaId: String, allowMissingLease: Boolean) {
        val id = mediaId.trim()
        if (id.isBlank() || (!allowMissingLease && StreamLeaseManager.lease(id) == null) || !inFlight.add(id)) return
        val now = System.currentTimeMillis()
        val previousAttempt = lastAttemptAt[id] ?: 0L
        if (now - previousAttempt < FAILED_REFRESH_RETRY_MS) {
            inFlight.remove(id)
            val retryAt = StreamLeaseManager.lease(id)?.refreshAfterMs
                ?.let { minOf(it, previousAttempt + FAILED_REFRESH_RETRY_MS) }
                ?: (previousAttempt + FAILED_REFRESH_RETRY_MS)
            scheduled.remove(id)?.cancel(false)
            scheduled[id] = executor.schedule({
                scheduled.remove(id)
                refreshNow(id, allowMissingLease)
            }, (retryAt - now).coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            return
        }
        val context = appContext
        if (context == null) {
            inFlight.remove(id)
            return
        }
        lastAttemptAt[id] = now
        executor.execute {
            try {
                val refreshed = PlayerHolder.refreshStreamForContinuity(context, id)
                if (!refreshed) {
                    Log.w(TAG, "backgroundRefreshMiss id=$id; current player item was left untouched")
                    scheduleRetry(id, allowMissingLease)
                } else {
                    freshReplacementAt[id] = System.currentTimeMillis()
                    if (freshReplacementAt.size > 64) {
                        val cutoff = System.currentTimeMillis() - FRESH_REPLACEMENT_TTL_MS
                        freshReplacementAt.entries.removeIf { it.value < cutoff }
                    }
                    Log.i(TAG, "backgroundRefreshReady id=$id; replacement is available to the next source request")
                }
            } catch (error: Throwable) {
                Log.w(TAG, "backgroundRefreshFailed id=$id; current player item was left untouched", error)
                scheduleRetry(id, allowMissingLease)
            } finally {
                inFlight.remove(id)
            }
        }
    }

    private fun scheduleRetry(mediaId: String, allowMissingLease: Boolean = false) {
        if (!allowMissingLease && StreamLeaseManager.lease(mediaId) == null) return
        scheduled.remove(mediaId)?.cancel(false)
        scheduled[mediaId] = executor.schedule({
            scheduled.remove(mediaId)
            refreshNow(mediaId, allowMissingLease)
        }, FAILED_REFRESH_RETRY_MS, TimeUnit.MILLISECONDS)
    }
}
