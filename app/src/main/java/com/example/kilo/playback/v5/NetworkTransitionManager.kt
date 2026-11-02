package com.example.kilo.playback.v5

import android.content.Context
import android.util.Log
import com.example.kilo.playbackrecovery.PlaybackDiagnostics
import com.example.kilo.PlaybackRepository
import com.example.kilo.QueuePreloader
import com.example.kilo.sourceprefetch.SourcePrefetchManager
import com.example.kilo.networkmonitor.NetworkHealthSnapshot
import com.example.kilo.networkmonitor.NetworkHealthMonitor

/** Refreshes only upcoming stream work after a network transition; it never replaces the active item. */
object NetworkTransitionManager {
    private const val TAG = "KiloNetworkTransition"
    private var lastSnapshot: NetworkHealthSnapshot? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var listenerRegistered = false

    fun start(context: Context) {
        appContext = context.applicationContext
        synchronized(this) {
            if (listenerRegistered) return
            listenerRegistered = true
        }
        NetworkHealthMonitor.addListener { next ->
            val previous = synchronized(this) { lastSnapshot.also { lastSnapshot = next } }
            if (previous == null || previous.transport == next.transport) return@addListener
            Log.i(TAG, "transport changed ${previous.transport}->${next.transport}; prewarming upcoming items")
            MainThreadPlayerGateway.dispatch { active ->
                val id = active.currentMediaItem?.mediaId.orEmpty()
                PlaybackDiagnostics.record(
                    "network_transition", id,
                    "from=${previous.transport}/${previous.grade} to=${next.transport}/${next.grade} " +
                        "playing=${active.playWhenReady} state=${active.playbackState}; active source left untouched"
                )
                if (id.isBlank() || !active.playWhenReady) return@dispatch
                if (StreamLeaseManager.refreshDue(id)) {
                    StreamLeaseRefreshCoordinator.refreshNow(id)
                }
                val upcoming = SourcePrefetchManager.upcomingIds(active, 4)
                    .filter { it != id && !it.startsWith("radio:") }
                if (upcoming.isNotEmpty()) {
                    // Expired cached leases will be resolved again by the existing source path.
                    upcoming.forEach { trackId -> if (StreamLeaseManager.refreshDue(trackId)) PlaybackRepository.invalidateResolvedStream(trackId) }
                    appContext?.let { QueuePreloader.enqueue(it, upcoming) }
                }
            }
        }
    }
}
