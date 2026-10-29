package com.example.kilo.androidauto.performance

import android.content.Context
import androidx.media3.common.Player
import com.example.kilo.observability.AndroidAutoMetrics
import com.example.kilo.playback.cache.QueueSnapshot
import com.example.kilo.playback.cache.QueueSnapshotCache
import com.example.kilo.playbackoptimization.AndroidAutoOptimizer
import com.example.kilo.playbackoptimization.ArtworkWarmupManager
import com.example.kilo.playbackoptimization.PreparedTrackRecord
import com.example.kilo.playbackoptimization.PreparedTrackStore
import com.example.kilo.playback.predictor.QueuePredictor

object AndroidAutoWarmupManager {
    fun initialize(context: Context) = AndroidAutoOptimizer.initialize(context.applicationContext)
    fun noteCarConnected() = AndroidAutoOptimizer.noteCarActivity()
    fun onBrowseActivity() = AndroidAutoOptimizer.noteCarActivity()
}

object AndroidAutoQueueMirror {
    fun capture(player: Player): QueueSnapshot {
        AndroidAutoWarmupManager.onBrowseActivity()
        return QueueSnapshotCache.capture(player)
    }

    fun predictedNext(player: Player) = QueuePredictor.nextTracks(player)
}

object AndroidAutoPreparedPool {
    fun get(mediaId: String): PreparedTrackRecord? = PreparedTrackStore.get(mediaId)
}

object AndroidAutoCacheManager {
    fun maxArtworkPixels(): Int = 256
    fun isCarMode(context: Context): Boolean = AndroidAutoOptimizer.isActive(context)
    fun shouldRunNonCriticalWork(context: Context): Boolean = AndroidAutoOptimizer.shouldRunNonCriticalWork(context)
    fun warmArtwork(uri: String) = AndroidAutoOptimizer.warmArtwork(uri)
    fun cachedArtwork(uri: String): ByteArray? = ArtworkWarmupManager.cachedArtwork(uri, maxPixels = 256)
}

object AndroidAutoPerformanceMode {
    fun enabled(context: Context): Boolean = AndroidAutoOptimizer.isActive(context)
    fun callbackFinished(durationMs: Long) = AndroidAutoMetrics.callback(durationMs)
}
