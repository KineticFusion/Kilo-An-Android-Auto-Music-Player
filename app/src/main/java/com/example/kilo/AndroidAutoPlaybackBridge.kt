package com.example.kilo

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Validates Android Auto items while retaining KILO's existing Media3 stream route. */
object AndroidAutoPlaybackBridge {
    private const val TAG = "KiloAutoBridge"
    private const val BROWSE_VALIDATION_TTL_MS = 1_000L
    private data class ValidatedBrowseItem(val item: MediaItem, val createdAtMs: Long)
    private val validatedBrowseItems = object : LruCache<String, ValidatedBrowseItem>(128) {}

    fun validate(item: MediaItem, callback: String): MediaItem? {
        val searchResult = SearchPlaybackCoordinator.adaptSearchItem(item, callback) ?: return null
        return validateNormalized(searchResult, callback)
    }

    private fun validateNormalized(item: MediaItem, callback: String): MediaItem? {
        val id = item.mediaId.trim()
        if (id.isBlank()) {
            Log.w(TAG, "reject callback=$callback reason=empty_media_id")
            return null
        }
        val sourceMetadata = item.mediaMetadata
        val playable = sourceMetadata.isPlayable == true
        val browsable = sourceMetadata.isBrowsable == true
        if (!playable && !browsable) {
            Log.w(TAG, "reject callback=$callback id=$id reason=not_playable_or_browsable")
            return null
        }

        // Car clients can request the same folder repeatedly while rendering a page.
        // Reuse only short-lived, immutable folder normalization; playable items always
        // pass through the normal validation path before reaching the existing player.
        val browseCacheKey = if (browsable) browseFingerprint(item) else null
        if (browseCacheKey != null) {
            val cached = validatedBrowseItems.get(browseCacheKey)
            if (cached != null && SystemClock.elapsedRealtime() - cached.createdAtMs <= BROWSE_VALIDATION_TTL_MS) {
                return cached.item
            }
            if (cached != null) validatedBrowseItems.remove(browseCacheKey)
        }

        val sourceUri = item.localConfiguration?.uri
        val uri = when {
            browsable -> sourceUri
            id.startsWith("radio:") -> sourceUri?.takeIf {
                it.scheme?.lowercase() in setOf("http", "https")
            }
            sourceUri?.scheme?.lowercase() in setOf("http", "https", "content", "file", "android.resource") -> sourceUri
            else -> Uri.parse("vybe://stream/$id")
        }
        if (playable && uri == null) {
            Log.w(TAG, "reject callback=$callback id=$id reason=invalid_uri")
            return null
        }

        val title = sourceMetadata.title?.toString()?.trim()?.takeIf(String::isNotBlank)
            ?: if (browsable) "KILO section" else id
        val artist = sourceMetadata.artist?.toString()?.trim()?.takeIf(String::isNotBlank)
            ?: if (playable) "Unknown artist" else null
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(sourceMetadata.subtitle)
            .setArtist(artist)
            .setAlbumTitle(sourceMetadata.albumTitle)
            .setDurationMs(sourceMetadata.durationMs?.takeIf { it > 0L } ?: if (playable) 210_000L else null)
            .setIsPlayable(playable)
            .setIsBrowsable(browsable)
            .setMediaType(if (browsable) MediaMetadata.MEDIA_TYPE_MIXED else MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(sourceMetadata.extras)
        if (browsable) {
            metadata.setFolderType(sourceMetadata.folderType ?: MediaMetadata.FOLDER_TYPE_MIXED)
        }
        sourceMetadata.artworkUri?.let { art ->
            if (art.scheme?.lowercase() in setOf("http", "https", "content", "android.resource")) {
                metadata.setArtworkUri(art)
            }
        }
        sourceMetadata.artworkData?.let { data ->
            metadata.setArtworkData(data, sourceMetadata.artworkDataType)
        }
        val ready = item.buildUpon()
            .setMediaId(id)
            .setMediaMetadata(metadata.build())
            .apply { if (uri != null) setUri(uri) }
            .build()
        val readyUri = ready.localConfiguration?.uri
        Log.d(TAG, "MEDIA_ITEM_VALID callback=$callback id=${ready.mediaId} uri=${readyUri?.buildUpon()?.clearQuery()?.build()} " +
            "title=${ready.mediaMetadata.title?.toString().orEmpty()} playable=${ready.mediaMetadata.isPlayable == true} " +
            "browsable=${ready.mediaMetadata.isBrowsable == true} durationMs=${ready.mediaMetadata.durationMs ?: -1L}")
        PlaybackMetrics.onAndroidAutoCallback(
            callback,
            ready.mediaId,
            readyUri?.toString().orEmpty(),
            ready.mediaMetadata.durationMs ?: -1L,
            "VALID"
        )
        if (browseCacheKey != null) {
            validatedBrowseItems.put(
                browseCacheKey,
                ValidatedBrowseItem(ready, SystemClock.elapsedRealtime())
            )
        }
        return ready
    }

    private fun browseFingerprint(item: MediaItem): String {
        val metadata = item.mediaMetadata
        return listOf(
            item.mediaId,
            item.localConfiguration?.uri?.toString().orEmpty(),
            metadata.title?.toString().orEmpty(),
            metadata.subtitle?.toString().orEmpty(),
            metadata.artist?.toString().orEmpty(),
            metadata.albumTitle?.toString().orEmpty(),
            metadata.artworkUri?.toString().orEmpty(),
            metadata.artworkData?.contentHashCode()?.toString().orEmpty(),
            metadata.isBrowsable.toString(),
            metadata.isPlayable.toString()
        ).joinToString("\u001f")
    }

    /** Warm the resolver cache before handing the valid KILO URI to the existing player path. */
    fun prepareForPlayback(context: Context, item: MediaItem): MediaItem? {
        val ready = validate(item, "playback_handoff") ?: return null
        if (ready.mediaMetadata.isPlayable != true) return ready
        val uri = ready.localConfiguration?.uri ?: return null
        if (uri.scheme != "vybe") return ready
        AutoSearchDiagnostics.searchClick(ready.mediaId)
        val startedAt = SystemClock.elapsedRealtime()
        val info = PlaybackFallbackCoordinator.resolve(context.applicationContext, ready.mediaId)
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val status = if (info == null) "resolver_miss_using_existing_player_path" else "stream_ready"
        Log.i(TAG, "handoff id=" + ready.mediaId + " uri=$uri resolverMs=$elapsed status=$status")
        PlaybackMetrics.onAndroidAutoCallback(
            "onSetMediaItems",
            ready.mediaId,
            uri.toString(),
            ready.mediaMetadata.durationMs ?: -1L,
            status
        )
        return ready
    }

    /** Recover a valid library item when AA asks for an ID that fell out of the browse index. */
    fun fallback(mediaId: String, currentItem: MediaItem? = null): MediaItem? {
        val id = mediaId.trim()
        if (id.isBlank() || id.startsWith("radio:")) return null
        currentItem?.takeIf { it.mediaId == id }?.let { return validate(it, "onGetItem_current") }
        val browsable = id == "root" || id.startsWith("tab_") ||
            id.startsWith("savedpl:") || id.startsWith("shelf:") ||
            id.startsWith("pl:") || id.startsWith("genre:") ||
            id.startsWith("mood:") || id.startsWith("moodradio:") || id.startsWith("artist:")
        val title = id.substringAfterLast(':').replace('_', ' ').ifBlank { "KILO" }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsPlayable(!browsable)
            .setIsBrowsable(browsable)
            .setMediaType(if (browsable) MediaMetadata.MEDIA_TYPE_MIXED else MediaMetadata.MEDIA_TYPE_MUSIC)
        if (browsable) metadata.setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
        val item = MediaItem.Builder()
            .setMediaId(id)
            .apply { if (!browsable) setUri("vybe://stream/$id") }
            .setMediaMetadata(metadata.build())
            .build()
        return validate(item, "onGetItem_recovery")
    }
}
