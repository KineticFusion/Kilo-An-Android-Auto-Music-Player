package com.example.kilo.androidauto

import android.content.Context
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Presentation-only Android Auto warmup for bundled artwork and cached browse categories. */
object AndroidAutoWarmupManager {
    private const val TAG = "KiloAutoWarmup"
    private val started = AtomicBoolean(false)
    @Volatile private var executor: ExecutorService? = null
    @Volatile private var context: Context? = null
    @Volatile private var browseLoader: ((String) -> Unit)? = null

    fun onServiceReady(context: Context, loadCachedBrowseData: (String) -> Unit) {
        this.context = context.applicationContext
        browseLoader = loadCachedBrowseData
        if (!started.compareAndSet(false, true)) return
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "KiloAndroidAutoWarmup").apply { isDaemon = true }
        }
        executor = worker
        worker.execute {
            runCatching {
                ArtworkCacheManager.initialize(context)
                ArtworkCacheManager.preload(context, worker)
                ConnectionReliabilityManager.startMonitoring(context)
                BrowsePreloadEngine.preload(context, worker) { parentId ->
                    loadCachedBrowseData(parentId)
                    Log.d(TAG, "browse_preloaded parent=$parentId")
                }
                AndroidAutoMetrics.logMemorySnapshot()
            }.onFailure { Log.w(TAG, "deferred Android Auto warmup skipped", it) }
        }
    }

    /** Revisit warm caches after a car reconnect; never starts or changes playback. */
    fun onCarConnected() {
        val appContext = context ?: return
        val worker = executor ?: return
        val loader = browseLoader ?: return
        BrowsePreloadEngine.preload(appContext, worker) { parentId ->
            loader(parentId)
            Log.d(TAG, "browse_reconnected parent=$parentId")
        }
    }

    fun shutdown() {
        ConnectionReliabilityManager.stopMonitoring()
        executor?.shutdown()
        executor = null
        browseLoader = null
        context = null
        started.set(false)
    }
}
