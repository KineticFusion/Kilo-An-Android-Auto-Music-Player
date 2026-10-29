package com.example.kilo.playback.source

import com.example.kilo.playbackoptimization.SourceFailureTracker as LegacyFailureTracker
import com.example.kilo.playbackoptimization.SourceHealthValidator as LegacyHealthValidator
import com.example.kilo.playbackoptimization.SourceLifecycleManager as LegacyLifecycleManager
import com.example.kilo.playbackoptimization.SourceLifecycleState
import com.example.kilo.playbackoptimization.SourceWarmPool as LegacyWarmPool
import com.example.kilo.playbackoptimization.SourceWarmupManager as LegacyWarmupManager
import com.example.kilo.playbackoptimization.SourceWarmupResolver
import com.example.kilo.playbackoptimization.WarmedSourceMetadata
import java.util.LinkedHashMap

typealias SourceState = SourceLifecycleState
typealias SourceLifecycleManager = LegacyLifecycleManager
typealias SourceWarmPool = LegacyWarmPool
typealias SourceHealthValidator = LegacyHealthValidator
typealias SourceFailureTracker = LegacyFailureTracker

/** Source registry, refresh facade and bounded alternate-source holder. */
object SourceRegistry {
    fun get(mediaId: String): WarmedSourceMetadata? = LegacyWarmupManager.warmedSource(mediaId)
    fun isHealthy(mediaId: String): Boolean = get(mediaId)?.let { LegacyHealthValidator.isUsable(it) } == true
}

object SourcePlatform {
    fun state(mediaId: String): SourceState = LegacyLifecycleManager.state(mediaId)
    fun transition(mediaId: String, state: SourceState) = LegacyLifecycleManager.transition(mediaId, state)
    fun recordFailure(mediaId: String) = LegacyFailureTracker.record(mediaId)
    fun failureCount(mediaId: String): Long = LegacyFailureTracker.count(mediaId)
    fun warm(mediaId: String, resolver: SourceWarmupResolver, retry: Boolean = false) =
        LegacyWarmPool.warm(mediaId, resolver, retry)
}

/** Requests a background refresh for a source that is stale or nearing expiry. */
object SourceRefreshEngine {
    @Volatile private var installedResolver: SourceWarmupResolver? = null

    /** Installed by the app wrapper so recovery can refresh a failed source off-thread. */
    fun install(resolver: SourceWarmupResolver) {
        installedResolver = resolver
    }

    fun refresh(mediaId: String) {
        val resolver = installedResolver ?: return
        refresh(mediaId, resolver)
    }

    fun refresh(mediaId: String, resolver: SourceWarmupResolver) {
        if (mediaId.isBlank()) return
        LegacyLifecycleManager.transition(mediaId, SourceState.REFRESHING)
        com.example.kilo.playbackoptimization.PreparedTrackRegistry.markRefreshing(mediaId)
        LegacyWarmPool.warm(mediaId, resolver, retryMiss = true)
    }
}

/** Holds short-lived alternate candidates in memory; callers must not persist signed URLs. */
object BackupSourceManager {
    private const val MAX_TRACKS = 32
    private val backups = object : LinkedHashMap<String, WarmedSourceMetadata>(MAX_TRACKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WarmedSourceMetadata>?): Boolean =
            size > MAX_TRACKS
    }

    fun remember(mediaId: String, source: WarmedSourceMetadata) = synchronized(backups) {
        if (mediaId.isNotBlank() && LegacyHealthValidator.isUsable(source)) backups[mediaId] = source
    }

    fun takeFallback(mediaId: String, rejectedUrl: String): WarmedSourceMetadata? = synchronized(backups) {
        val source = backups[mediaId] ?: return@synchronized null
        if (!LegacyHealthValidator.isUsable(source) || source.streamUrl == rejectedUrl) {
            backups.remove(mediaId)
            null
        } else source
    }

    fun clear(mediaId: String) = synchronized(backups) { backups.remove(mediaId) }
}
