package com.example.kilo

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.kilo.streaming.StreamInfo
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val SEARCH_CACHE_TTL_MS = 15 * 60 * 1_000L

/** Bounded, short-lived metadata and resolver cache for items returned by search. */
object SearchResultCache {
    data class Entry(
        val mediaId: String,
        val mediaItem: MediaItem,
        val artwork: Uri?,
        val sourceMetadata: MediaMetadata,
        val resolvedStream: StreamInfo?,
        val expiresAtEpochMs: Long
    )

    private const val MAX_ENTRIES = 160
    private val entries = LinkedHashMap<String, Entry>(32, 0.75f, true)

    @Synchronized
    fun get(mediaId: String): Entry? {
        val entry = entries[mediaId] ?: return null
        if (entry.expiresAtEpochMs <= System.currentTimeMillis()) {
            entries.remove(mediaId)
            return null
        }
        val stream = entry.resolvedStream?.takeIf { it.expiresAtEpochMs <= 0L || it.expiresAtEpochMs > System.currentTimeMillis() }
        return if (stream === entry.resolvedStream) entry else entry.copy(resolvedStream = stream).also { entries[mediaId] = it }
    }

    @Synchronized
    fun remember(item: MediaItem) {
        val id = item.mediaId.trim()
        if (id.isBlank()) return
        entries[id] = Entry(
            mediaId = id,
            mediaItem = item,
            artwork = item.mediaMetadata.artworkUri,
            sourceMetadata = item.mediaMetadata,
            resolvedStream = entries[id]?.resolvedStream,
            expiresAtEpochMs = System.currentTimeMillis() + SEARCH_CACHE_TTL_MS
        )
        trimLocked()
    }

    @Synchronized
    fun rememberResolved(mediaId: String, info: StreamInfo) {
        val id = mediaId.trim()
        val current = entries[id] ?: return
        entries[id] = current.copy(
            resolvedStream = info,
            expiresAtEpochMs = System.currentTimeMillis() + SEARCH_CACHE_TTL_MS
        )
        trimLocked()
    }

    @Synchronized
    fun clearExpired() {
        val now = System.currentTimeMillis()
        entries.entries.removeAll { it.value.expiresAtEpochMs <= now }
    }

    private fun trimLocked() {
        while (entries.size > MAX_ENTRIES) entries.entries.iterator().run { next(); remove() }
    }
}

/** Strict validation for a playable search result before Android Auto can display it. */
object MediaItemValidator {
    data class Result(val valid: Boolean, val reason: String? = null)

    fun validate(item: MediaItem): Result {
        val id = item.mediaId.trim()
        if (!isValidMediaId(id)) return Result(false, "invalid_media_id")
        val metadata = item.mediaMetadata
        if (metadata.isBrowsable == true || metadata.isPlayable != true) return Result(false, "not_playable")
        if (metadata.title?.toString()?.isNotBlank() != true) return Result(false, "missing_title")
        if (metadata.artist?.toString()?.isNotBlank() != true) return Result(false, "missing_artist")
        val uri = item.localConfiguration?.uri ?: return Result(false, "missing_playback_uri")
        if (uri.scheme?.lowercase() !in setOf("vybe", "http", "https", "content", "file", "android.resource")) {
            return Result(false, "invalid_playback_uri")
        }
        return Result(true)
    }

    fun isValidMediaId(id: String): Boolean = id.isNotBlank() && id.length <= 256 &&
        id.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f || it == '/' || it == '\\' }
}

/** Converts metadata-light search responses into complete, driver-safe playable items. */
object AndroidAutoSearchAdapter {
    data class Adaptation(val item: MediaItem?, val error: String? = null)

    fun adapt(item: MediaItem, cached: MediaItem? = null): Adaptation {
        val id = item.mediaId.trim()
        if (!MediaItemValidator.isValidMediaId(id)) return Adaptation(null, "invalid_media_id")

        val metadata = item.mediaMetadata
        val fallback = cached?.mediaMetadata
        if (metadata.isPlayable == false) return Adaptation(null, "not_playable")
        val title = metadata.title?.toString()?.trim()?.takeIf(String::isNotBlank)
            ?: fallback?.title?.toString()?.trim()?.takeIf(String::isNotBlank)
            ?: return Adaptation(null, "missing_title")
        val artist = metadata.artist?.toString()?.trim()?.takeIf(String::isNotBlank)
            ?: fallback?.artist?.toString()?.trim()?.takeIf(String::isNotBlank)
            ?: return Adaptation(null, "missing_artist")
        if (metadata.isBrowsable == true) return Adaptation(null, "search_result_is_browsable")

        val candidateUri = item.localConfiguration?.uri
        val fallbackUri = cached?.localConfiguration?.uri
        val uri = listOfNotNull(candidateUri, fallbackUri).firstOrNull {
            it.scheme?.lowercase() in setOf("vybe", "http", "https", "content", "file", "android.resource")
        } ?: Uri.parse("vybe://stream/$id")
        val builder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(metadata.albumTitle?.toString()?.takeIf(String::isNotBlank) ?: fallback?.albumTitle?.toString())
            .setSubtitle(metadata.subtitle?.toString()?.takeIf(String::isNotBlank) ?: fallback?.subtitle?.toString())
            .setDurationMs(metadata.durationMs?.takeIf { it > 0L } ?: fallback?.durationMs?.takeIf { it > 0L } ?: 210_000L)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(metadata.extras ?: fallback?.extras)
        (metadata.artworkUri ?: fallback?.artworkUri)?.let(builder::setArtworkUri)
        val artworkData = metadata.artworkData ?: fallback?.artworkData
        if (artworkData != null) {
            val artworkType = if (metadata.artworkData != null) metadata.artworkDataType
                else fallback?.artworkDataType ?: metadata.artworkDataType
            builder.setArtworkData(artworkData, artworkType)
        }

        val adapted = item.buildUpon()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(builder.build())
            .build()
        val validation = MediaItemValidator.validate(adapted)
        return if (validation.valid) Adaptation(adapted) else Adaptation(null, validation.reason)
    }
}

/** Search-result boundary used by the existing Android Auto item-validation wrapper. */
object SearchPlaybackCoordinator {
    private const val TAG = "AutoSearchDiagnostics"

    fun isSearchItem(item: MediaItem, callback: String): Boolean =
        callback.contains("search:", ignoreCase = true) || SearchResultCache.get(item.mediaId.trim()) != null

    fun adaptSearchItem(item: MediaItem, callback: String): MediaItem? {
        val id = item.mediaId.trim()
        val cache = SearchResultCache.get(id)
        if (!isSearchItem(item, callback)) return item

        if (callback.contains("onSetMediaItems", ignoreCase = true) || callback.contains("onAddMediaItems", ignoreCase = true)) {
            AutoSearchDiagnostics.searchClick(id)
        }
        val cachedItem = cache?.mediaItem
        val adaptation = AndroidAutoSearchAdapter.adapt(item, cachedItem)
        val ready = adaptation.item
        if (ready == null) {
            AutoSearchDiagnostics.androidAutoError(id, adaptation.error ?: "invalid_search_result")
            Log.w(TAG, "search_result_rejected mediaId=$id reason=${adaptation.error}")
            return null
        }
        SearchResultCache.remember(ready)
        AutoSearchDiagnostics.searchResultReady(id)
        SearchSourcePreloader.offer(id, callback)
        return ready
    }

}

/** Resolves only a few visible search results on a background executor. */
object SearchSourcePreloader {
    private val lock = Any()
    private val pending = LinkedHashSet<String>()
    private var pendingBatch = ""
    private var scheduled: ScheduledFuture<*>? = null
    private val scheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "kilo-aa-search-debounce").apply { isDaemon = true }
    }
    private val workers = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "kilo-aa-search-preload").apply { isDaemon = true }
    }

    fun offer(mediaId: String, batchKey: String) {
        if (!MediaItemValidator.isValidMediaId(mediaId) || mediaId.startsWith("radio:")) return
        synchronized(lock) {
            if (batchKey != pendingBatch) {
                pending.clear()
                pendingBatch = batchKey
            }
            pending += mediaId
            scheduled?.cancel(false)
            scheduled = scheduler.schedule({ drainBatch() }, 300L, TimeUnit.MILLISECONDS)
        }
    }

    private fun drainBatch() {
        val ids = synchronized(lock) {
            val snapshot = pending.take(3)
            pending.clear()
            snapshot
        }
        val context = runCatching { KiloApplication.appContext }.getOrNull() ?: return
        ids.forEach { mediaId ->
            workers.execute {
                PlaybackFallbackCoordinator.resolve(context, mediaId)
            }
        }
    }
}

/** Reuses the existing resolver/cache and retries a failed warm-up once. */
object PlaybackFallbackCoordinator {
    fun resolve(context: Context, mediaId: String): StreamInfo? {
        SearchResultCache.get(mediaId)?.resolvedStream?.let { cached ->
            if (isUsable(cached)) return cached
        }
        ResolvedTrackCache.peek(mediaId)?.let { cached ->
            SearchResultCache.rememberResolved(mediaId, cached)
            return cached
        }

        repeat(2) { attempt ->
            val started = SystemClock.elapsedRealtime()
            val resolved = runCatching { PlayerHolder.resolveForWarmup(context.applicationContext, mediaId) }
                .onFailure { AutoSearchDiagnostics.sourceFailure(mediaId, "resolver_exception_${it.javaClass.simpleName}") }
                .getOrNull()
                ?.takeIf(::isUsable)
            AutoSearchDiagnostics.resolutionTime(mediaId, SystemClock.elapsedRealtime() - started, resolved != null)
            if (resolved != null) {
                SearchResultCache.rememberResolved(mediaId, resolved)
                return resolved
            }
            if (attempt == 0) AutoSearchDiagnostics.sourceFailure(mediaId, "resolver_retry")
        }

        val cachedFallback = ResolvedTrackCache.peek(mediaId)
            ?: SearchResultCache.get(mediaId)?.resolvedStream?.takeIf(::isUsable)
        if (cachedFallback == null) {
            AutoSearchDiagnostics.sourceFailure(mediaId, "retry_and_cache_miss")
            AutoSearchDiagnostics.playbackFailure(mediaId, "source_unavailable_after_retry")
        }
        return cachedFallback
    }

    private fun isUsable(info: StreamInfo): Boolean = info.url.isNotBlank() &&
        (info.expiresAtEpochMs <= 0L || info.expiresAtEpochMs > System.currentTimeMillis())
}

/** Structured diagnostics for Android Auto search selections and background resolution. */
object AutoSearchDiagnostics {
    private const val TAG = "AutoSearchDiagnostics"

    fun searchClick(mediaId: String) = Log.i(TAG, "search_click mediaId=$mediaId")
    fun searchResultReady(mediaId: String) = Log.d(TAG, "search_result_ready mediaId=$mediaId")
    fun resolutionTime(mediaId: String, durationMs: Long, ready: Boolean) =
        Log.i(TAG, "resolution_time mediaId=$mediaId duration_ms=$durationMs ready=$ready")
    fun sourceFailure(mediaId: String, reason: String) =
        Log.e(TAG, "source_failure mediaId=$mediaId reason=$reason")
    fun playbackFailure(mediaId: String, reason: String) =
        Log.e(TAG, "playback_failure mediaId=$mediaId reason=$reason")
    fun androidAutoError(mediaId: String, reason: String) =
        Log.e(TAG, "android_auto_error mediaId=$mediaId reason=$reason")
}
