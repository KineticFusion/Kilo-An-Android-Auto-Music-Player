@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.kilo

import android.app.NotificationChannel
import com.example.kilo.streaming.*
import com.example.kilo.profile.*
import com.example.kilo.search.SearchEnhancer
import com.example.kilo.search.SearchAnalytics
import com.example.kilo.androidauto.AndroidAutoHomeLayouts
import com.example.kilo.androidauto.AndroidAutoUiModule
import com.example.kilo.androidauto.AndroidAutoWarmupManager
import com.example.kilo.androidauto.ArtworkCacheManager
import com.example.kilo.androidauto.BrowsePreloadEngine
import com.example.kilo.androidauto.ConnectionReliabilityManager
import com.example.kilo.androidauto.R as AndroidAutoR
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.example.kilo.audioanalysis.AudioAnalysisDatabaseProvider
import com.example.kilo.audioanalysis.AudioAnalysisService
import com.example.kilo.audioanalysis.TrackAnalysisEntity
import com.example.kilo.playback.stability.MediaSourceAudit
import com.example.kilo.playback.stability.PlaybackStabilityManager
import com.example.kilo.playback.stability.RecoveryCoordinator
import com.example.kilo.search.preparation.SearchWarmupEngine
import com.example.kilo.mood.preparation.MoodWarmupEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

private const val TAG = "VybeAuto"
private fun safeUriForLog(uri: Uri?): String = uri?.buildUpon()?.clearQuery()?.build()?.toString().orEmpty()
private const val ROOT = "root"
private const val AUTO_STYLE_SUPPORTED = AndroidAutoUiModule.STYLE_SUPPORTED
private const val AUTO_BROWSABLE_HINT = AndroidAutoUiModule.BROWSABLE_HINT
private const val AUTO_PLAYABLE_HINT = AndroidAutoUiModule.PLAYABLE_HINT
private const val AUTO_STYLE_LIST = AndroidAutoUiModule.STYLE_LIST
private const val AUTO_STYLE_GRID = AndroidAutoUiModule.STYLE_GRID
private const val TAB_HOME = "tab_home"
private const val TAB_SEARCH = "tab_search"
private const val TAB_BROWSE = "tab_browse"
private const val TAB_LIBRARY = "tab_library"
private const val TAB_OFFLINE = "tab_offline"
private const val TAB_ALBUMS = "tab_albums"
private const val TAB_SONGS = "tab_songs"
private const val TAB_PICKS = "tab_picks"
private const val TAB_SHELVES = "tab_shelves"
private const val TAB_LIKED = "tab_liked"
private const val TAB_RECENT = "tab_recent"
private const val TAB_CONTINUE = "tab_continue"
private const val TAB_PLAYLISTS = "tab_playlists"
private const val TAB_LISTEN_AGAIN = "tab_listen_again"
private const val TAB_HEARD_SHORTS = "tab_heard_shorts"
private const val TAB_TRENDING = "tab_trending"

/**
 * Expired/rejected stream URLs are not transient. Invalidate their resolution and let
 * PlayerHolder rebuild the MediaItem/MediaSource. Retrying the failed byte range in-place
 * is unsafe because a refreshed URL can select a different container (for example, Opus
 * in WebM -> AAC in MP4) while the existing extractor still expects the original format.
 */
private class PlaybackLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(5) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        var cause: Throwable? = loadErrorInfo.exception
        while (cause != null) {
            val status = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            if (status == 403 || status == 410) {
                // A rejected signed URL is recoverable, but must be retried by rebuilding
                // the source. A DataSource-level retry reuses the open extractor and its
                // byte position; the refreshed resolver may return another container.
                val mediaId = PlayerHolder.refreshRejectedStreamSource(loadErrorInfo.loadEventInfo.dataSpec)
                val canRebuildSource = mediaId != null && loadErrorInfo.errorCount == 1
                Log.w(
                    "KiloPlayback",
                    "http=$status rejectedSource id=${mediaId.orEmpty()} " +
                        "attempt=${loadErrorInfo.errorCount} rebuildSource=$canRebuildSource"
                )
                // Propagate immediately to PlayerHolder.onPlayerError. Its existing
                // recovery replaces only the current item and preserves queue, position,
                // and playWhenReady while Media3 creates an extractor for the new format.
                return C.TIME_UNSET
            }
            cause = cause.cause
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
private const val TAB_RADIO = "tab_radio"
private const val TAB_RADIO_BROWSE = "tab_radio_browse"
private const val TAB_RADIO_FAVORITES = "tab_radio_favorites"
private const val TAB_RADIO_RECENT = "tab_radio_recent"
private const val TAB_RADIO_CUSTOM = "tab_radio_custom"
private const val TAB_GENRES = "tab_genres"
private const val TAB_MOODS = "tab_moods"
private const val TAB_ARTISTS = "tab_profile_artists"
private val LIKE_COMMAND = SessionCommand("vybe_like", Bundle.EMPTY)
private val QUEUE_NEXT_COMMAND = SessionCommand("vybe_queue_next", Bundle.EMPTY)
private val CLOSE_APP_COMMAND = SessionCommand("vybe_close_app", Bundle.EMPTY)
private val MOOD_FOR_ME_COMMAND = SessionCommand("vybe_mood_for_me", Bundle.EMPTY)

const val ACTION_TOGGLE_PLAY = "com.example.kilo.ACTION_TOGGLE_PLAY"
const val ACTION_NEXT_TRACK = "com.example.kilo.ACTION_NEXT_TRACK"
const val ACTION_PREV_TRACK = "com.example.kilo.ACTION_PREV_TRACK"
const val ACTION_CLOSE_APP = "com.example.kilo.ACTION_CLOSE_APP"
private const val NOTIF_CHANNEL_ID = "vybe_playback_channel"
private const val NOTIF_ID = 4041
private const val MAX_ANDROID_AUTO_CHILD_PAGE_SIZE = 50

/** Exposes the active timed lyric as session subtitle metadata without replacing the player or queue. */
private class AndroidAutoLyricsSessionPlayer(delegate: Player) : ForwardingPlayer(delegate) {
    private val sessionListeners = CopyOnWriteArrayList<Player.Listener>()
    @Volatile private var currentLyric: String? = null

    override fun addListener(listener: Player.Listener) {
        sessionListeners.addIfAbsent(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        sessionListeners.remove(listener)
        super.removeListener(listener)
    }

    override fun getMediaMetadata(): MediaMetadata {
        val line = currentLyric ?: return super.getMediaMetadata()
        return super.getMediaMetadata().buildUpon().setSubtitle(line).build()
    }

    fun setCurrentLyric(line: String?) {
        val normalized = line?.trim()?.takeIf(String::isNotBlank)
        if (currentLyric == normalized) return
        currentLyric = normalized
        val metadata = mediaMetadata
        sessionListeners.forEach { listener ->
            listener.onMediaMetadataChanged(metadata)
        }
    }
}

@androidx.media3.common.util.UnstableApi
fun SongItemClean.toMediaItem(): MediaItem {
    val vid = videoId.ifEmpty { id }
    LosslessSource.register(this, vid)
    PlaybackRepository.remember(this)
    val fingerprint = listOf(title, artist, album, durationMs.toString(), coverUrl).joinToString("\u001f")
    PlaybackRepository.mediaItem(vid, fingerprint)?.let { return it }
    val item = MediaItemCache.getOrCreate(vid, fingerprint) {
        PlaybackMetrics.onMediaItemCreated(vid)
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setDurationMs(durationMs)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(autoBrowseStyleExtras(isBrowsable = false))
        if (coverUrl.isNotEmpty()) {
            runCatching { Uri.parse(coverUrl) }.getOrNull()?.let { meta.setArtworkUri(it) }
        }
        MediaItem.Builder()
            .setMediaId(vid)
            .setUri("vybe://stream/$vid")
            .setMediaMetadata(meta.build())
            .build()
    }
    PlaybackRepository.rememberMediaItem(vid, fingerprint, item)
    return item
}

fun MediaItem.toSong(idx: Int = 0): SongItemClean {
    val md = mediaMetadata
    val isRadio = mediaId.startsWith("radio:")
    val title = md.title?.toString() ?: "Unknown"
    val artist = md.artist?.toString() ?: "Unknown"
    return SongItemClean(
        id = mediaId,
        videoId = mediaId.takeUnless { isRadio }.orEmpty(),
        title = title,
        artist = artist,
        album = if (isRadio) "Internet Radio" else md.albumTitle?.toString() ?: "YouTube Music",
        coverUrl = md.artworkUri?.toString().orEmpty(),
        audioUrl = if (isRadio) localConfiguration?.uri?.toString().orEmpty() else "",
        themeColor = YtRepo.paletteColors[idx % YtRepo.paletteColors.size],
        durationMs = md.durationMs ?: 210000L
    )
}

private fun folderItem(
    id: String,
    title: String,
    subtitle: String? = null,
    art: String? = null,
    childStyle: Int = AUTO_STYLE_LIST,
    artworkData: ByteArray? = null
): MediaItem = AndroidAutoUiModule.folder(
    id = id,
    title = title,
    subtitle = subtitle,
    artworkUri = art?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?.takeIf { it.scheme?.lowercase() in setOf("http", "https", "content", "android.resource") },
    artworkData = artworkData,
    childStyle = childStyle
)

private fun autoBrowseStyleExtras(isBrowsable: Boolean, style: Int = AUTO_STYLE_LIST): Bundle = Bundle().apply {
    putAll(AndroidAutoUiModule.styleExtras(isBrowsable, style))
}

private fun listStyleLibraryParams(params: LibraryParams?, childStyle: Int = AUTO_STYLE_LIST): LibraryParams =
    AndroidAutoUiModule.libraryParams(params, childStyle)

private fun <T> List<T>.pageSafely(page: Int, pageSize: Int): List<T> {
    if (page < 0) return emptyList()
    val effectivePageSize = when {
        pageSize <= 0 -> MAX_ANDROID_AUTO_CHILD_PAGE_SIZE
        else -> pageSize.coerceAtMost(MAX_ANDROID_AUTO_CHILD_PAGE_SIZE)
    }
    val from = page.toLong() * effectivePageSize.toLong()
    if (from >= size) return emptyList()
    val to = minOf(from + effectivePageSize.toLong(), size.toLong()).toInt()
    return subList(from.toInt(), to)
}

object Favorites {
    private var prefs: SharedPreferences? = null
    private var cache = JSONObject()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("vybe_prefs", Context.MODE_PRIVATE)
        prefs = p
        cache = try {
            JSONObject(p.getString("favorites", "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    @Synchronized
    fun clearForFirstUpdate() {
        cache = JSONObject()
        prefs?.edit()?.remove("favorites")?.apply()
        listeners.forEach { it() }
    }

    @Synchronized
    fun ids(): Set<String> = cache.keys().asSequence().toSet()

    @Synchronized
    fun isLiked(id: String): Boolean = cache.has(id)

    fun toggle(song: SongItemClean) {
        val id = song.videoId.ifEmpty { song.id }
        synchronized(this) {
            if (cache.has(id)) {
                cache.remove(id)
            } else {
                cache.put(
                    id,
                    JSONObject()
                        .put("t", song.title)
                        .put("a", song.artist)
                        .put("c", song.coverUrl)
                        .put("d", song.durationMs)
                        .put("ts", System.currentTimeMillis())
                )
            }
            prefs?.edit()?.putString("favorites", cache.toString())?.apply()
        }
        listeners.forEach { it() }
    }

    @Synchronized
    fun songs(): List<SongItemClean> {
        val out = mutableListOf<Pair<Long, SongItemClean>>()
        val keys = cache.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val o = cache.optJSONObject(k) ?: continue
            out += Pair(
                o.optLong("ts"),
                SongItemClean(
                    id = k,
                    videoId = k,
                    title = o.optString("t"),
                    artist = o.optString("a"),
                    album = "YouTube Music",
                    coverUrl = o.optString("c"),
                    durationMs = o.optLong("d", 210000L)
                )
            )
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }
}

object SessionStore {
    class Restored(val items: List<MediaItem>, val index: Int, val posMs: Long)
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(ctx: Context) {
        if (prefs == null) {
            prefs = ctx.applicationContext.getSharedPreferences("vybe_prefs", Context.MODE_PRIVATE)
        }
    }

    fun save(player: Player) {
        val p = prefs ?: return
        val n = player.mediaItemCount
        if (n == 0 || player.currentMediaItem?.mediaId?.startsWith("radio:") == true) return
        val idx = player.currentMediaItemIndex.coerceIn(0, n - 1)
        val start = (idx - 10).coerceAtLeast(0)
        val end = minOf(n, start + 100)
        val arr = JSONArray()
        for (i in start until end) {
            val s = player.getMediaItemAt(i).toSong(i)
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("t", s.title)
                    .put("a", s.artist)
                    .put("c", s.coverUrl)
                    .put("d", s.durationMs)
            )
        }
        p.edit()
            .putString("queue", arr.toString())
            .putInt("index", idx - start)
            .putLong("pos", player.currentPosition.coerceAtLeast(0L))
            .apply()
    }

    @Synchronized
    fun record(mediaItem: MediaItem?) {
        val p = prefs ?: return
        mediaItem ?: return
        if (mediaItem.mediaId.isBlank() || mediaItem.mediaId.startsWith("radio:")) return
        val song = mediaItem.toSong()
        val saved = runCatching { JSONArray(p.getString("recent_songs", "[]")) }.getOrDefault(JSONArray())
        val recent = JSONArray().put(
            JSONObject()
                .put("id", song.id)
                .put("t", song.title)
                .put("a", song.artist)
                .put("c", song.coverUrl)
                .put("d", song.durationMs)
        )
        for (i in 0 until saved.length()) {
            val item = saved.optJSONObject(i) ?: continue
            if (item.optString("id") == song.id || recent.length() >= 30) continue
            recent.put(item)
        }
        p.edit().putString("recent_songs", recent.toString()).apply()
    }

    @Synchronized
    fun recentSongs(): List<SongItemClean> {
        val p = prefs ?: return emptyList()
        val saved = runCatching { JSONArray(p.getString("recent_songs", "[]")) }.getOrDefault(JSONArray())
        return (0 until saved.length()).mapNotNull { i ->
            val o = saved.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) return@mapNotNull null
            SongItemClean(
                id = id,
                videoId = id,
                title = o.optString("t"),
                artist = o.optString("a"),
                album = "YouTube Music",
                coverUrl = o.optString("c"),
                durationMs = o.optLong("d", 210000L)
            )
        }
    }

    fun load(): Restored? {
        val p = prefs ?: return null
        return try {
            val arr = JSONArray(p.getString("queue", null) ?: return null)
            if (arr.length() == 0) return null
            val items = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SongItemClean(
                    id = o.getString("id"),
                    videoId = o.getString("id"),
                    title = o.optString("t"),
                    artist = o.optString("a"),
                    album = "YouTube Music",
                    coverUrl = o.optString("c"),
                    durationMs = o.optLong("d", 210000L)
                ).toMediaItem()
            }
            Restored(
                items,
                p.getInt("index", 0).coerceIn(0, items.size - 1),
                p.getLong("pos", 0L)
            )
        } catch (e: Exception) {
            Log.e(TAG, "resume load failed", e)
            null
        }
    }
}

object HomeCache {
    @Volatile
    var shelves: List<HomeShelf> = emptyList()
    /** Keep personalized unplayed shelves separate from remote catalog refreshes. */
    @Volatile
    var discoveryShelves: List<HomeShelf> = emptyList()
    @Volatile
    private var time = 0L
    @Volatile
    private var lastAttempt = 0L

    fun set(list: List<HomeShelf>) {
        shelves = list
        val personalizedShelves = list.filter { shelf ->
            val title = shelf.title.trim()
            title.isNotBlank() && !title.contains("mood", ignoreCase = true) &&
                !title.endsWith(" mix", ignoreCase = true) &&
                !title.startsWith("For your ", ignoreCase = true)
        }
        if (personalizedShelves.isNotEmpty() || list.isEmpty()) discoveryShelves = personalizedShelves
        time = System.currentTimeMillis()
    }

    fun get(): List<HomeShelf> {
        val now = System.currentTimeMillis()
        val stale = shelves.isEmpty() || now - time > 900_000L
        if (stale && now - lastAttempt > 30_000L) {
            lastAttempt = now
            runCatching { YtRepo.fetchHomeCatalog().shelves }
                .getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
                ?.let(::set)
        }
        return shelves
    }
}

object PlayerHolder {
    private const val MAX_PLAYBACK_RETRIES = 2
    private const val STARTUP_WARM_BYTES = 96L * 1024L
    private data class ErrorRecovery(val positionMs: Long, val shouldResume: Boolean, val attempt: Int)
    private var player: ExoPlayer? = null
    private var bandwidthMeter: DefaultBandwidthMeter? = null
    private var analysisService: AudioAnalysisService? = null
    @Volatile private var analysisAppContext: Context? = null
    @Volatile private var analysisResultListener: ((TrackAnalysisEntity) -> Unit)? = null
    private val retryAttempts = ConcurrentHashMap<String, Int>()
    private val unavailableTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val lastKnownPositionMs = ConcurrentHashMap<String, Long>()
    private val pendingErrorRecoveries = ConcurrentHashMap<String, ErrorRecovery>()
    private val qualifiedHistoryIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastAudibleTrackId: String? = null
    @Volatile private var lastAutoplayReadyTrackId: String? = null
    private val idleRecoveryAttempts = ConcurrentHashMap<String, Int>()
    private val idleRecoveryScheduled = ConcurrentHashMap.newKeySet<String>()
    private val bgPool = Executors.newCachedThreadPool()
    @Volatile private var handleCallAudioFocus = true
    @Volatile private var lastPlayIntent = false

    fun isRetrying(videoId: String): Boolean =
        (retryAttempts[videoId] ?: 0) in 1..MAX_PLAYBACK_RETRIES

    fun lastPlaybackPosition(videoId: String): Long {
        val activePlayer = player
        val livePosition = activePlayer
            ?.takeIf { it.currentMediaItem?.mediaId == videoId }
            ?.currentPosition
            ?.coerceAtLeast(0L)
            ?: 0L
        return maxOf(lastKnownPositionMs[videoId] ?: 0L, livePosition)
    }

    @Synchronized
    fun currentMediaItemOrNull(): MediaItem? = player?.currentMediaItem

    fun estimatedBandwidthKbps(): Long {
        val estimate = bandwidthMeter?.bitrateEstimate ?: -1L
        return if (estimate > 0L) estimate / 1_000L else -1L
    }

    // Live PCM analysis is intentionally disabled; cached analysis remains available to DJ mode.
    fun setDjAnalysisEnabled(enabled: Boolean) = Unit
    fun setAnalysisMediaId(mediaId: String) = Unit
    fun setAnalysisResultListener(listener: ((TrackAnalysisEntity) -> Unit)?) { analysisResultListener = listener }

    fun loadAnalysis(trackId: String, executor: java.util.concurrent.Executor): java.util.concurrent.CompletableFuture<TrackAnalysisEntity?> =
        getAudioAnalysisService()?.loadCachedAsync(trackId, executor)
            ?: java.util.concurrent.CompletableFuture.completedFuture(null)

    @Synchronized
    private fun getAudioAnalysisService(): AudioAnalysisService? {
        analysisService?.let { return it }
        val app = analysisAppContext ?: return null
        return AudioAnalysisService(AudioAnalysisDatabaseProvider.get(app).trackAnalysisDao()).also {
            analysisService = it
        }
    }

    @Synchronized
    fun get(context: Context): ExoPlayer {
        player?.let { return it }
        val app = context.applicationContext
        val playbackPreferences = app.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
        handleCallAudioFocus = playbackPreferences.getBoolean("pause_for_calls", true)
        Favorites.init(app)
        SessionStore.init(app)
        PlayedSongHistory.init(app)

        val meter = DefaultBandwidthMeter.Builder(app).build()
        bandwidthMeter = meter
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(6000)
            .setReadTimeoutMs(30000)
            .setTransferListener(object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                    if (isNetwork && dataSpec.position > 0L) {
                        val headers = (source as? HttpDataSource)?.responseHeaders.orEmpty()
                        val acceptRanges = headers.entries.firstOrNull { it.key.equals("Accept-Ranges", true) }
                            ?.value?.joinToString()
                        val contentRange = headers.entries.firstOrNull { it.key.equals("Content-Range", true) }
                            ?.value?.joinToString()
                        Log.d(
                            "SeekBenchmark",
                            "network range request position=${dataSpec.position} length=${dataSpec.length} " +
                                "acceptRanges=${acceptRanges ?: "not reported"} contentRange=${contentRange ?: "not reported"}"
                        )
                    }
                }
                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            })

        val cachedFactory = CacheDataSource.Factory()
            .setCache(AudioCache.get(app))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app, http))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val resolver = ResolvingDataSource.Resolver { spec ->
            resolve(app, spec)
        }

        analysisAppContext = app

        val p = ExoPlayer.Builder(app)
            .setBandwidthMeter(meter)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // Keep the generous rebuffer target for stable playback, but start
                    // the first audio sooner once 1.5 seconds are safely buffered.
                    .setBufferDurationsMs(30_000, 90_000, 1_500, 5_000)
                    .build()
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    ResolvingDataSource.Factory(cachedFactory, resolver)
                ).setLoadErrorHandlingPolicy(PlaybackLoadErrorPolicy())
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                handleCallAudioFocus
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.setPlaybackSpeed(playbackPreferences.getFloat("playback_speed", 1f).coerceIn(0.5f, 2f))
        p.setSkipSilenceEnabled(playbackPreferences.getBoolean("skip_silence_enabled", false))
        p.repeatMode = if (playbackPreferences.getBoolean("repeat_current_track", false)) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_ALL
        }

        val positionHandler = Handler(Looper.getMainLooper())
        val positionSampler = object : Runnable {
            override fun run() {
                p.currentMediaItem?.mediaId?.takeIf { p.isPlaying }?.let { id ->
                    val position = p.currentPosition.coerceAtLeast(0L)
                    lastKnownPositionMs[id] = position
                    if (position >= 30_000L && qualifiedHistoryIds.add(id)) {
                        val playedItem = p.currentMediaItem
                        bgPool.execute {
                            PlayedSongHistory.record(id)
                            SessionStore.record(playedItem)
                        }
                    }
                }
                positionHandler.postDelayed(this, 250L)
            }
        }
        positionHandler.post(positionSampler)

        fun logPlaybackSnapshot(event: String) {
            val item = p.currentMediaItem
            val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: -1L
            Log.i(
                "KiloPlayback",
                "$event id=${item?.mediaId.orEmpty()} title=${item?.mediaMetadata?.title?.toString().orEmpty()} " +
                    "state=${when (p.playbackState) { Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"; Player.STATE_READY -> "READY"; Player.STATE_ENDED -> "ENDED"; else -> p.playbackState.toString() }} " +
                    "playWhenReady=${p.playWhenReady} isPlaying=${p.isPlaying} positionMs=${p.currentPosition} " +
                    "bufferedMs=${p.bufferedPosition} durationMs=$duration bufferPercent=${p.bufferedPercentage} " +
                    "bandwidthKbps=${(meter.bitrateEstimate / 1_000L).takeIf { it > 0L } ?: -1L} " +
                    "queue=${p.currentMediaItemIndex + 1}/${p.mediaItemCount}"
            )
        }

        var lastObservedPlaybackState = p.playbackState
        p.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val currentId = p.currentMediaItem?.mediaId
                if (playWhenReady) {
                    lastPlayIntent = true
                } else {
                    lastPlayIntent = false
                }
                Log.i(
                    "KiloPlayback",
                    "playWhenReadyChanged id=${p.currentMediaItem?.mediaId.orEmpty()} " +
                        "value=$playWhenReady reason=$reason " +
                        "lastIntent=$lastPlayIntent state=${p.playbackState} " +
                        "suppression=${p.playbackSuppressionReason} positionMs=${p.currentPosition}"
                )
                PlaybackStartupMetrics.playerState(
                    p.currentMediaItem?.mediaId, p.playbackState, playWhenReady, p.isPlaying
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val previousPlaybackState = lastObservedPlaybackState
                lastObservedPlaybackState = playbackState
                logPlaybackSnapshot("stateChanged")
                PlaybackPerformanceLayer.onPlayerState(app, p, playbackState, p.isPlaying)
                PlaybackStartupMetrics.playerState(
                    p.currentMediaItem?.mediaId, playbackState, p.playWhenReady, p.isPlaying
                )
                if (playbackState == Player.STATE_READY && p.playWhenReady) {
                    lastAutoplayReadyTrackId = p.currentMediaItem?.mediaId
                }
                if (playbackState == Player.STATE_READY) {
                    val id = p.currentMediaItem?.mediaId
                    id?.let { idleRecoveryAttempts.remove(it) }
                    val recovery = id?.let { pendingErrorRecoveries.remove(it) }
                    if (id != null && RecoveryCoordinator.state(id) == RecoveryCoordinator.State.RECOVERING) {
                        RecoveryCoordinator.markRecovered(id)
                    }
                    if (id != null && recovery != null) {
                        val duration = p.duration.takeIf { it > 0L && it != C.TIME_UNSET }
                        val targetPosition = recovery.positionMs.coerceAtLeast(0L).let { position ->
                            if (duration != null) position.coerceAtMost((duration - 1_000L).coerceAtLeast(0L)) else position
                        }
                        Log.w(
                            "KiloPlayback",
                            "errorRecoveryReady id=$id attempt=${recovery.attempt} targetPositionMs=$targetPosition " +
                                "durationMs=${duration ?: -1L} wasPlaying=${recovery.shouldResume}"
                        )
                        // The retry path applies this seek before prepare. Seeking again
                        // here can trigger a second buffer cycle after READY, which sounds
                        // like a brief pause even though the stream has recovered.
                        lastKnownPositionMs[id] = targetPosition
                        if (recovery.shouldResume && lastPlayIntent && !p.playWhenReady) p.play()
                    }
                }
                if (playbackState == Player.STATE_IDLE) {
                    p.currentMediaItem?.mediaId?.let { id ->
                        scheduleUnexpectedIdleRecovery(p, id, positionHandler, previousPlaybackState)
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    p.currentMediaItem?.mediaId?.let { lastKnownPositionMs[it] = newPosition.positionMs.coerceAtLeast(0L) }
                }
                Log.i(
                    "KiloPlayback",
                    "positionDiscontinuity reason=$reason old=${oldPosition.positionMs}ms " +
                        "new=${newPosition.positionMs}ms item=${newPosition.mediaItemIndex}"
                )
            }

            override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) {
                Log.i("KiloPlayback", "trackSelectionParametersChanged $parameters")
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                Log.i("KiloAuto", "TRACK_SELECTION id=${p.currentMediaItem?.mediaId.orEmpty()} tracks=$tracks")
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                Log.i("KiloPlayback", "timelineChanged reason=$reason windows=${timeline.windowCount} periods=${timeline.periodCount}")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    mediaItem?.mediaId?.let { PlaybackStartupMetrics.selected(it, "automatic_queue") }
                }
                mediaItem?.mediaId?.let { lastKnownPositionMs[it] = p.currentPosition.coerceAtLeast(0L) }
                Log.i(
                    "KiloPlayback",
                    "mediaItemTransition reason=$reason id=${mediaItem?.mediaId.orEmpty()} " +
                        "title=${mediaItem?.mediaMetadata?.title?.toString().orEmpty()} uri=${mediaItem?.localConfiguration?.uri?.scheme}"
                )
                // Error recovery replaces the current item in the playlist. Keep its
                // failure count across that synthetic transition so repeated 403s
                // cannot restart the same retry forever.
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    mediaItem?.mediaId?.let {
                        retryAttempts.remove(it)
                        idleRecoveryAttempts.remove(it)
                    }
                }
                SessionStore.save(p)
                ensureServiceRunning(app)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.i(
                    "KiloPlayback",
                    "isPlayingChanged id=${p.currentMediaItem?.mediaId.orEmpty()} " +
                        "isPlaying=$isPlaying playWhenReady=${p.playWhenReady} " +
                        "state=${p.playbackState} suppression=${p.playbackSuppressionReason} " +
                        "positionMs=${p.currentPosition} " +
                        "bufferedMs=${(p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)}"
                )
                PlaybackStartupMetrics.playerState(
                    p.currentMediaItem?.mediaId, p.playbackState, p.playWhenReady, isPlaying
                )
                PlaybackPerformanceLayer.onPlayerState(app, p, p.playbackState, isPlaying)
                if (isPlaying) {
                    val playingId = p.currentMediaItem?.mediaId
                    if (playingId != null) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (p.currentMediaItem?.mediaId == playingId && p.isPlaying && p.currentPosition >= 10_000L) {
                                retryAttempts.remove(playingId)
                                idleRecoveryAttempts.remove(playingId)
                                unavailableTrackIds.remove(playingId)
                            }
                        }, 20_000L)
                    }
                    ensureServiceRunning(app)
                } else {
                    p.currentMediaItem?.mediaId?.let { id ->
                        lastKnownPositionMs[id] = maxOf(
                            lastKnownPositionMs[id] ?: 0L,
                            p.currentPosition.coerceAtLeast(0L)
                        )
                    }
                    SessionStore.save(p)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val failedItem = p.currentMediaItem
                val id = failedItem?.mediaId
                Log.e("KiloAuto", "PLAYER_ERROR code=${error.errorCode} name=${error.errorCodeName} " +
                    "id=${id.orEmpty()} uri=${safeUriForLog(failedItem?.localConfiguration?.uri)} " +
                    "state=${p.playbackState} playWhenReady=${p.playWhenReady}", error)
                PlaybackStartupMetrics.playerError(id, error.errorCode, error.errorCodeName)
                if (id == null || failedItem == null) return
                pendingErrorRecoveries.remove(id)
                Log.e("KiloPlayback", "Playback error ${error.errorCodeName} code=${error.errorCode} for $id", error)
                logPlaybackSnapshot("playerError")
                if (!RecoveryCoordinator.beginExistingRecovery(
                        id,
                        "owner=player_error code=${error.errorCode} name=${error.errorCodeName} " +
                            "carMode=${com.example.kilo.androidauto.performance.AndroidAutoPerformanceMode.enabled(app)}"
                    )
                ) {
                    Log.w("KiloPlayback", "playerError recovery suppressed id=$id reason=recovery_already_active")
                    return
                }
                if (!id.startsWith("radio:")) {
                    PlaybackRepository.invalidateResolvedStream(id)
                    LosslessSource.invalidate(id)
                    FastStream.invalidate(id)
                    QueuePreloader.forget(id)
                }
                val attempt = retryAttempts.merge(id, 1) { previous, increment -> previous + increment } ?: 1
                val retryDelay = (100L * (attempt - 1)).takeIf { attempt <= MAX_PLAYBACK_RETRIES }
                val failureText = generateSequence<Throwable>(error) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString(" ")
                // A resolver miss can recover on the NewPipe/legacy pass after invalidation.
                // Only YouTube's explicit unavailable response should skip immediately.
                val streamUnavailable = failureText.contains("video is unavailable", ignoreCase = true)
                val shouldResume = p.playWhenReady || lastPlayIntent
                val retryPositionMs = maxOf(
                    lastKnownPositionMs[id] ?: 0L,
                    p.currentPosition.coerceAtLeast(0L)
                )
                val currentIndex = p.currentMediaItemIndex
                if (streamUnavailable || retryDelay == null) {
                    unavailableTrackIds.add(id)
                    retryAttempts.remove(id)
                    val nextIndex = (1 until p.mediaItemCount)
                        .map { offset -> (currentIndex + offset) % p.mediaItemCount }
                        .firstOrNull { index ->
                            val nextId = p.getMediaItemAt(index).mediaId
                            nextId != id && nextId !in unavailableTrackIds
                        }
                    if (nextIndex != null) {
                        Log.w("KiloPlayback", "Skipping unavailable stream id=$id; continuing at queue index=$nextIndex")
                        RecoveryCoordinator.markFailed(id, "unavailable_stream_skipped")
                        MediaSourceAudit.beforeMutation("prepare:skip_unavailable", p, id)
                        p.seekTo(nextIndex, 0L)
                        p.prepare()
                        if (shouldResume) p.play()
                    } else {
                        Log.e("KiloPlayback", "No other playable queue item after stream failure id=$id")
                        RecoveryCoordinator.markFailed(id, "unavailable_stream_and_no_queue_candidate")
                        p.pause()
                    }
                    return
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    if (p.currentMediaItem?.mediaId == id && p.currentMediaItemIndex == currentIndex) {
                        val resumePositionMs = maxOf(
                            retryPositionMs,
                            lastKnownPositionMs[id] ?: 0L
                        )
                        // A failed MediaSource retains its already-resolved URL. Replacing
                        // only the current MediaItem makes Media3 ask the resolver for a
                        // fresh stream, while preserving the queue and the listener's
                        // last confirmed position.
                        val shouldResumeNow = shouldResume && (p.playWhenReady || lastPlayIntent)
                        pendingErrorRecoveries[id] = ErrorRecovery(resumePositionMs, shouldResumeNow, attempt)
                        val originalUri = failedItem.localConfiguration?.uri
                        val retryItem = failedItem.buildUpon().apply {
                            if (originalUri?.scheme == "vybe") setUri(originalUri)
                        }.build()
                        MediaSourceAudit.beforeMutation("replace_media_item:player_error_recovery", p, id)
                        p.replaceMediaItem(currentIndex, retryItem)
                        p.seekTo(currentIndex, resumePositionMs)
                        // Preserve Media3's playWhenReady intent across source recovery.
                        // An explicit pause here adds a dead-air gap and can lose autoplay.
                        if (shouldResumeNow && !p.playWhenReady) p.play()
                        MediaSourceAudit.beforeMutation("prepare:player_error_recovery", p, id)
                        p.prepare()
                        Log.w(
                            "KiloPlayback",
                            "errorRecoveryPrepared id=$id attempt=$attempt positionMs=$resumePositionMs " +
                                "wasPlaying=$shouldResumeNow uriScheme=${originalUri?.scheme.orEmpty()}"
                        )
                    } else {
                        RecoveryCoordinator.cancelExistingRecovery(id, "track_or_queue_changed_before_retry")
                    }
                }, retryDelay)
            }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioPositionAdvancing(
                eventTime: AnalyticsListener.EventTime,
                playoutStartSystemTimeMs: Long
            ) {
                lastAudibleTrackId = p.currentMediaItem?.mediaId
                PlaybackStartupMetrics.firstAudioOutput(
                    p.currentMediaItem?.mediaId,
                    "audio_position_advancing"
                )
            }
        })
        player = p
        PlaybackMetrics.onPlayerReady()
        return p
    }

    fun setCallAudioFocus(enabled: Boolean) {
        handleCallAudioFocus = enabled
        player?.let { p ->
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                enabled
            )
        }
    }

    private fun ensureServiceRunning(app: Context) {
        runCatching {
            val intent = Intent(app, VybeMediaService::class.java)
            ContextCompat.startForegroundService(app, intent)
        }
    }

    private fun scheduleUnexpectedIdleRecovery(
        p: ExoPlayer,
        id: String,
        handler: Handler,
        previousState: Int
    ) {
        val appContext = analysisAppContext ?: return
        val hadStarted = id == lastAudibleTrackId || id == lastAutoplayReadyTrackId ||
            p.currentPosition > 0L || (lastKnownPositionMs[id] ?: 0L) > 0L
        // A cold-path failure can fall from BUFFERING back to IDLE before the first
        // audible frame. The old guard only recovered tracks that had already played,
        // leaving the first selection permanently stuck with playWhenReady=true.
        val failedDuringStartup = !hadStarted && previousState == Player.STATE_BUFFERING
        if ((!hadStarted && !failedDuringStartup) || !p.playWhenReady || p.playerError != null || p.mediaItemCount == 0) return
        if (idleRecoveryScheduled.contains(id)) return
        val protection = PlaybackStabilityManager.unexpectedIdleRecoveryDecision(id)
        if (protection.shouldBlock) {
            Log.w(
                "KiloPlayback",
                "unexpectedIdleRecovery suppressed id=$id reason=${protection.reason} " +
                    "lastHealthyPositionMs=${protection.latestHealthyPositionMs} " +
                    "lastHealthyBufferedMs=${protection.latestHealthyBufferedMs} " +
                    "carMode=${com.example.kilo.androidauto.performance.AndroidAutoPerformanceMode.enabled(appContext)}"
            )
            return
        }
        if (!RecoveryCoordinator.beginExistingRecovery(
                id,
                "owner=unexpected_idle previousState=$previousState carMode=" +
                    com.example.kilo.androidauto.performance.AndroidAutoPerformanceMode.enabled(appContext)
            )
        ) {
            Log.w("KiloPlayback", "unexpectedIdleRecovery suppressed id=$id reason=recovery_already_active")
            return
        }
        if (!idleRecoveryScheduled.add(id)) {
            RecoveryCoordinator.cancelExistingRecovery(id, "duplicate_idle_recovery_request")
            return
        }
        val attempt = idleRecoveryAttempts.merge(id, 1) { previous, increment -> previous + increment } ?: 1
        val maxAttempts = if (failedDuringStartup) 1 else 3
        if (attempt > maxAttempts) {
            idleRecoveryScheduled.remove(id)
            RecoveryCoordinator.markFailed(id, "unexpected_idle_recovery_attempt_limit")
            Log.e(
                "KiloPlayback",
                "unexpectedIdleRecovery exhausted id=" + id +
                    " playWhenReady=" + p.playWhenReady +
                    " positionMs=" + p.currentPosition
            )
            return
        }
        val resumePositionMs = maxOf(lastKnownPositionMs[id] ?: 0L, p.currentPosition.coerceAtLeast(0L))
        Log.w(
            "KiloPlayback",
            "unexpectedIdleRecovery scheduled id=$id attempt=$attempt startup=$failedDuringStartup " +
                "previousState=$previousState positionMs=$resumePositionMs"
        )
        handler.postDelayed({
            idleRecoveryScheduled.remove(id)
            if (p.currentMediaItem?.mediaId != id) {
                RecoveryCoordinator.cancelExistingRecovery(id, "track_changed_before_idle_recovery")
                return@postDelayed
            }
            if (!p.playWhenReady || p.playbackState != Player.STATE_IDLE || p.playerError != null) {
                if (p.playbackState == Player.STATE_READY && p.isPlaying) {
                    RecoveryCoordinator.markRecovered(id)
                } else {
                    RecoveryCoordinator.cancelExistingRecovery(id, "idle_recovery_no_longer_applicable")
                }
                return@postDelayed
            }
            runCatching {
                p.seekTo(resumePositionMs)
                MediaSourceAudit.beforeMutation("prepare:unexpected_idle_recovery", p, id)
                p.prepare()
                p.play()
                Log.w("KiloPlayback", "unexpectedIdleRecovery started id=$id attempt=$attempt positionMs=$resumePositionMs")
            }.onFailure { error ->
                RecoveryCoordinator.markFailed(id, "unexpected_idle_recovery_exception:${error.javaClass.simpleName}")
                Log.e("KiloPlayback", "unexpectedIdleRecovery failed id=$id attempt=$attempt", error)
            }
        }, if (failedDuringStartup) 650L else 100L * (attempt - 1))
    }

    fun prefetch(ctx: Context, videoId: String, onComplete: ((Boolean) -> Unit)? = null) {
        if (videoId.isBlank()) {
            onComplete?.invoke(false)
            return
        }
        bgPool.execute {
            var succeeded = false
            try {
                val info = resolveStreamInfo(ctx, videoId) ?: return@execute
                val prefetchFailure = AudioCache.prefetch(ctx, videoId, info)
                val responseCode = generateSequence<Throwable>(prefetchFailure) { it.cause }
                    .mapNotNull { (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode }
                    .firstOrNull()
                if (responseCode == 403) {
                    Log.w("KiloPlayback", "prefetch stream rejected id=$videoId http=403; refreshing before selection")
                    PlaybackRepository.invalidateResolvedStream(videoId)
                    LosslessSource.invalidate(videoId)
                    FastStream.invalidate(videoId)
                    QueuePreloader.forget(videoId)
                    val refreshed = resolveStreamInfo(ctx, videoId)
                    if (refreshed != null) {
                        val retryFailure = AudioCache.prefetch(ctx, videoId, refreshed)
                        if (retryFailure == null) {
                            succeeded = true
                            Log.i("KiloPlayback", "prefetch refresh ready id=$videoId")
                        } else {
                            Log.w("KiloPlayback", "prefetch refresh failed id=$videoId", retryFailure)
                        }
                    } else {
                        Log.w("KiloPlayback", "prefetch refresh found no stream id=$videoId")
                    }
                } else if (prefetchFailure != null) {
                    Log.w("KiloPlayback", "prefetch failed id=$videoId", prefetchFailure)
                } else {
                    succeeded = true
                }
            } catch (error: Throwable) {
                Log.w("KiloPlayback", "prefetch failed id=$videoId", error)
            } finally {
                onComplete?.invoke(succeeded)
            }
        }
    }

    fun resolveForWarmup(ctx: Context, videoId: String): StreamInfo? =
        resolveStreamInfo(ctx.applicationContext, videoId)

    /**
     * Prepare the same small first-audio range the player will read. This moves
     * stale-URL detection and the first bytes off the tap-to-audio critical path.
     */
    fun prepareSourceForWarmup(ctx: Context, videoId: String): StreamInfo? {
        val app = ctx.applicationContext
        var info = resolveStreamInfo(app, videoId) ?: return null
        var failure = AudioCache.prefetch(app, videoId, info, STARTUP_WARM_BYTES)
        var status = httpStatusCode(failure)

        if (status == 403 || status == 410) {
            Log.w("KiloPlayback", "startupWarmSourceRejected id=$videoId http=$status; refreshing once")
            invalidateStreamSource(videoId)
            info = resolveStreamInfo(app, videoId) ?: return null
            failure = AudioCache.prefetch(app, videoId, info, STARTUP_WARM_BYTES)
            status = httpStatusCode(failure)
        }

        if (failure == null) {
            Log.i("KiloPlayback", "startupAudioBytesReady id=$videoId bytes=$STARTUP_WARM_BYTES")
        } else {
            Log.d("KiloPlayback", "startupAudioWarmMiss id=$videoId http=${status ?: -1}", failure)
            if (status == 403 || status == 410) {
                invalidateStreamSource(videoId)
                return null
            }
        }
        return info
    }

    private fun httpStatusCode(error: Throwable?): Int? {
        var cause = error
        while (cause != null) {
            (cause as? HttpDataSource.InvalidResponseCodeException)?.let { return it.responseCode }
            cause = cause.cause
        }
        return null
    }

    private fun invalidateStreamSource(videoId: String) {
        PlaybackRepository.invalidateResolvedStream(videoId)
        LosslessSource.invalidate(videoId)
        FastStream.invalidate(videoId)
        QueuePreloader.forget(videoId)
    }

    /**
     * Refresh a rejected URL before Media3 retries its load. The original URI is normally
     * still vybe://; the cache key is a fallback for loads whose event reports the resolved
     * HTTP URI instead.
     */
    fun refreshRejectedStreamSource(dataSpec: DataSpec): String? {
        val videoId = dataSpec.uri
            .takeIf { it.scheme.equals("vybe", ignoreCase = true) }
            ?.lastPathSegment
            ?.takeIf { it.isNotBlank() }
            ?: dataSpec.key?.let { key ->
                Regex("^(.+)-[0-9a-fA-F]{8}-[^-]+-full-v\\d+$")
                    .matchEntire(key)
                    ?.groupValues
                    ?.getOrNull(1)
            }
            ?.takeIf { it.isNotBlank() }
            ?: return null
        invalidateStreamSource(videoId)
        return videoId
    }

    private fun resolveStreamInfo(ctx: Context, videoId: String): StreamInfo? {
        // Prefer the repository's already-resolved stream for this loaded track. It is
        // process-local and expiry-checked, so the existing resolver remains the fallback.
        val repositoryCandidate = PlaybackRepository.resolvedStream(videoId)
        val repositoryHit = repositoryCandidate?.takeIf(FastStream::validate)
        if (repositoryCandidate != null && repositoryHit == null) {
            Log.w("KiloPlayback", "cached stream URL rejected id=$videoId; resolving a fresh source")
            PlaybackRepository.invalidateResolvedStream(videoId)
            LosslessSource.invalidate(videoId)
            FastStream.invalidate(videoId)
        }
        val cacheHit = repositoryHit != null || ResolvedTrackCache.peek(videoId) != null
        PlaybackMetrics.onResolverStarted(videoId, cacheHit)
        val startedAt = SystemClock.elapsedRealtime()
        repositoryHit?.let { cached ->
            PlaybackMetrics.onResolverFinished(videoId, startedAt, cached)
            PlaybackWarmupManager.onResolverReady()
            return cached
        }
        val resolver = StreamResolver(listOf(
            StreamSource { id ->
                val candidate = LosslessSource.peek(id) ?: LosslessSource.resolve(ctx, id)
                if (candidate == null || FastStream.validate(candidate)) candidate
                else {
                    Log.w("KiloPlayback", "lossless stream URL rejected id=$id; trying existing fallback")
                    LosslessSource.invalidate(id)
                    null
                }
            },
            StreamSource { id -> FastStream.get(id, { YtRepo.fetchStream(it) }, ctx) }
        ))
        val info = try {
            resolver.resolve(videoId)
        } catch (error: Throwable) {
            PlaybackMetrics.onResolverFinished(videoId, startedAt, null, error.message)
            PlaybackWarmupManager.onResolverReady()
            throw error
        }
        if (info != null) PlaybackRepository.rememberResolvedStream(videoId, info)
        PlaybackMetrics.onResolverFinished(videoId, startedAt, info)
        PlaybackWarmupManager.onResolverReady()
        return info
    }

    private fun resolve(ctx: Context, spec: DataSpec): DataSpec {
        val uri = spec.uri
        if (uri.scheme != "vybe") return spec
        val videoId = uri.lastPathSegment ?: throw IOException("Bad vybe uri: $uri")
        val sourceStartedAt = SystemClock.elapsedRealtime()
        Log.i("KiloAuto", "MEDIA_SOURCE_CREATE id=$videoId uri=$uri position=${spec.position} length=${spec.length}")
        val prefs = ctx.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
        val downloads = runCatching { JSONObject(prefs.getString("downloaded_song_uris", "{}") ?: "{}") }.getOrNull()
        val localUriStr = sequenceOf(videoId, LosslessSource.song(videoId)?.id)
            .filterNotNull().distinct().mapNotNull { downloads?.optString(it)?.takeIf { value -> value.isNotBlank() && value != "null" } }
            .firstOrNull()
        if (localUriStr != null) {
            Log.i("KiloPlayback", "streamResolved id=$videoId source=download uriScheme=${Uri.parse(localUriStr).scheme}")
            Log.i("KiloAuto", "MEDIA_SOURCE_READY id=$videoId source=download resolverMs=${SystemClock.elapsedRealtime() - sourceStartedAt} uriScheme=${Uri.parse(localUriStr).scheme}")
            return spec.buildUpon().setUri(Uri.parse(localUriStr)).build()
        }
        val resolveStartedAt = SystemClock.elapsedRealtime()
        val info = resolveStreamInfo(ctx, videoId)
            ?: run {
                Log.e("KiloAuto", "MEDIA_SOURCE_FAILED id=$videoId resolverMs=${SystemClock.elapsedRealtime() - sourceStartedAt} reason=no_playable_stream")
                throw IOException("No playable stream for $videoId")
            }
        val streamHost = Uri.parse(info.url).host.orEmpty()
        Log.i("KiloAuto", "MEDIA_SOURCE_READY id=$videoId resolverMs=${SystemClock.elapsedRealtime() - sourceStartedAt} " +
            "host=$streamHost expiresAtMs=${info.expiresAtEpochMs} bitrateKbps=${info.bitrateKbps}")
        Log.i(
            "KiloPlayback",
            "streamResolved id=$videoId host=$streamHost codec=${info.codec} bitrateKbps=${info.bitrateKbps} " +
                "expiresAtMs=${info.expiresAtEpochMs} ttlMs=${(info.expiresAtEpochMs - System.currentTimeMillis()).takeIf { info.expiresAtEpochMs > 0L } ?: -1L} " +
                "resolveMs=${SystemClock.elapsedRealtime() - resolveStartedAt} rangeStart=${spec.position}"
        )
        val refererHeaders = if (streamHost.endsWith("youtube.com") || streamHost.endsWith("googlevideo.com")) {
            mapOf(
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com",
                "Accept-Encoding" to "identity"
            )
        } else emptyMap()
        return spec.buildUpon()
            .setUri(Uri.parse(info.url))
            .setKey(AudioCache.keyFor(videoId, info.url))
            .setHttpRequestHeaders(spec.httpRequestHeaders + info.requestHeaders + refererHeaders + mapOf("User-Agent" to info.userAgent))
            .build()
    }
}

class VybeMediaService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var lyricsSessionPlayer: AndroidAutoLyricsSessionPlayer? = null
    private var lyricMediaId: String? = null
    private var lyricLines: List<LyricLine> = emptyList()
    private val io = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var currentTrackSnapshot: MediaItem? = null
    @Volatile private var autoPlayOnCarConnectHandled = false
    @Volatile private var notificationArtworkId: String? = null
    @Volatile private var notificationArtwork: Bitmap? = null
    @Volatile private var artworkLoadInProgress = false
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private val index = ConcurrentHashMap<String, MediaItem>()
    private val siblings = ConcurrentHashMap<String, List<MediaItem>>()
    private val searchCache = ConcurrentHashMap<String, List<MediaItem>>()
    private val homeRefreshInProgress = AtomicBoolean(false)
    private val pendingCatalogRefreshes = ConcurrentHashMap.newKeySet<String>()
    private val pendingBrowseBuilds = ConcurrentHashMap.newKeySet<String>()
    private val lastCatalogRefreshAt = ConcurrentHashMap<String, Long>()

    private val favListener: () -> Unit = { main.post { updateLayout(); postPlaybackNotification() } }
    private val carBrowsePreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "auto_layout_mode" || key == AndroidAutoHomeLayouts.ORDER_PREFERENCE) {
            main.post {
                val count = runCatching { validMediaItems(ROOT, carRootItems()).size }.getOrDefault(0)
                session?.notifyChildrenChanged(ROOT, count, null)
            }
        }
    }
    private val layoutListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            DriveRecapStore.mediaItemTransition(mediaItem, reason, session?.player?.isPlaying == true)
            currentTrackSnapshot = mediaItem
            updateAndroidAutoLyricLine(mediaItem, session?.player?.currentPosition ?: 0L)
            updateLayout()
            postPlaybackNotification()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            postPlaybackNotification()
            val isPlaying = session?.player?.isPlaying == true
            if (isPlaying && wifiLock?.isHeld == false) wifiLock?.acquire()
            else if (!isPlaying && wifiLock?.isHeld == true) wifiLock?.release()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            DriveRecapStore.playbackChanged(isPlaying)
            postPlaybackNotification()
            if (isPlaying && wifiLock?.isHeld == false) wifiLock?.acquire()
            else if (!isPlaying && wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private val lyricMetadataPoller = object : Runnable {
        override fun run() {
            val player = PlayerHolder.get(this@VybeMediaService)
            updateAndroidAutoLyricLine(player.currentMediaItem, player.currentPosition)
            main.postDelayed(this, 500L)
        }
    }

    private fun updateAndroidAutoLyricLine(mediaItem: MediaItem?, positionMs: Long) {
        val id = mediaItem?.mediaId.orEmpty()
        if (id != lyricMediaId) {
            lyricMediaId = id
            lyricLines = emptyList()
            lyricsSessionPlayer?.setCurrentLyric(null)
            if (mediaItem != null && id.isNotBlank() && !id.startsWith("radio:")) {
                LyricsRepository.prefetch(mediaItem.toSong())
            }
        }
        if (mediaItem == null || id.isBlank() || id.startsWith("radio:")) {
            lyricsSessionPlayer?.setCurrentLyric(null)
            return
        }
        val cachedLines = LyricsRepository.cached(mediaItem.toSong())
        if (cachedLines.isNotEmpty()) lyricLines = cachedLines
        val currentLine = lyricLines.asSequence()
            .filter { it.timeMs >= 0L && it.timeMs <= positionMs && it.text.isNotBlank() }
            .maxByOrNull { it.timeMs }
        lyricsSessionPlayer?.setCurrentLyric(currentLine?.text)
    }

    /** Warm a bounded page of visible Android Auto tracks off the browse callback. */
    private fun warmVisibleAndroidAutoTracks(items: List<MediaItem>, section: String) {
        val visible = items.asSequence()
            .filter { item ->
                item.mediaMetadata.isPlayable == true &&
                    item.mediaId.isNotBlank() && !item.mediaId.startsWith("radio:")
            }
            .distinctBy { it.mediaId }
            .take(8)
            .toList()
        if (visible.isEmpty()) return
        runCatching {
            io.execute {
                val songs = visible.mapNotNull { item -> runCatching { item.toSong() }.getOrNull() }
                if (songs.isNotEmpty()) {
                    // The existing visible-source warmer resolves/cache-warms only 96 KB
                    // per track and caps concurrent work; queue and player stay untouched.
                    when {
                        section.contains("search", ignoreCase = true) -> SearchWarmupEngine.prepare(
                            applicationContext,
                            section.substringAfter("search:", section).take(160),
                            songs
                        )
                        section.contains("mood", ignoreCase = true) -> MoodWarmupEngine.prepare(
                            applicationContext,
                            section.substringAfter("mood:", section).take(80),
                            songs
                        )
                        else -> PlaybackPerformanceLayer.prepareVisible(
                            applicationContext,
                            songs,
                            "android_auto:$section"
                        )
                    }
                    Log.d(TAG, "VISIBLE_TRACK_WARMUP section=$section count=${songs.size} bytes=98304")
                }
            }
        }.onFailure { error ->
            Log.d(TAG, "visible track warmup skipped section=$section", error)
        }
    }

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val p = session?.player ?: return
            when (intent?.action) {
                ACTION_TOGGLE_PLAY -> if (p.isPlaying) p.pause() else p.play()
                ACTION_NEXT_TRACK -> if (p.mediaItemCount > 0) {
                    p.seekToNextMediaItem()
                    p.play()
                }
                ACTION_PREV_TRACK -> if (p.mediaItemCount > 0) {
                    p.seekToPreviousMediaItem()
                    p.play()
                }
                ACTION_CLOSE_APP -> closeAppCompletely()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Favorites.init(this)
        SessionStore.init(this)
        DriveRecapStore.init(this)
        YtRepo.refreshCookieFromWebView()
        createNotifChannel()

        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE_PLAY)
            addAction(ACTION_NEXT_TRACK)
            addAction(ACTION_PREV_TRACK)
            addAction(ACTION_CLOSE_APP)
        }
        ContextCompat.registerReceiver(this, notifReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val player = PlayerHolder.get(this)
        currentTrackSnapshot = player.currentMediaItem
        lyricsSessionPlayer = AndroidAutoLyricsSessionPlayer(player)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, requireNotNull(lyricsSessionPlayer), AutoCallback())
            .setSessionActivity(open)
            .build()
        updateAndroidAutoLyricLine(player.currentMediaItem, player.currentPosition)
        main.post(lyricMetadataPoller)
        PlaybackWarmupManager.onMediaSessionReady()
        AndroidAutoWarmupManager.onServiceReady(this) { parentId ->
            runCatching { childrenFromCache(parentId) }
                .onFailure { Log.w(TAG, "Android Auto browse warmup skipped parent=$parentId", it) }
        }
        Log.i(TAG, "MediaLibrarySession created sessionAvailable=${session != null} playerItems=${player.mediaItemCount}")
        
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KiloPlaybackWifiLock")
        
        player.addListener(layoutListener)
        Favorites.addListener(favListener)
        getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(carBrowsePreferenceListener)
        updateLayout()
        postPlaybackNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        postPlaybackNotification()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "KILO Playback Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active music playback controls and quick close"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun postPlaybackNotification() {
        val activeSession = session ?: return
        val p = activeSession.player
        val item = p.currentMediaItem
        val title = item?.mediaMetadata?.title?.toString() ?: "KILO Music"
        val artist = item?.mediaMetadata?.artist?.toString() ?: "Ready to stream"
        // Keep the notification's play/pause action aligned with playback intent
        // during the brief period when the next item is buffering.
        val isPlaying = p.playWhenReady
        val artworkUri = item?.mediaMetadata?.artworkUri
        if (item?.mediaId != notificationArtworkId) {
            notificationArtworkId = item?.mediaId
            notificationArtwork = null
        }
        if (artworkUri != null && notificationArtwork == null && !artworkLoadInProgress) {
            artworkLoadInProgress = true
            val requestedId = item?.mediaId
            io.execute {
                val bitmap = runCatching {
                    val connection = URL(artworkUri.toString()).openConnection() as HttpURLConnection
                    connection.connectTimeout = 4000
                    connection.readTimeout = 5000
                    connection.instanceFollowRedirects = true
                    try {
                        connection.inputStream.use { stream -> BitmapFactory.decodeStream(stream) }
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
                main.post {
                    artworkLoadInProgress = false
                    if (requestedId != null && requestedId == session?.player?.currentMediaItem?.mediaId) {
                        notificationArtworkId = requestedId
                        notificationArtwork = bitmap
                        if (bitmap != null) postPlaybackNotification()
                    } else {
                        postPlaybackNotification()
                    }
                }
            }
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prevPending = PendingIntent.getBroadcast(
            this, 11, Intent(ACTION_PREV_TRACK).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playPausePending = PendingIntent.getBroadcast(
            this, 12, Intent(ACTION_TOGGLE_PLAY).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextPending = PendingIntent.getBroadcast(
            this, 13, Intent(ACTION_NEXT_TRACK).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val closePending = PendingIntent.getBroadcast(
            this, 14, Intent(ACTION_CLOSE_APP).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(openAppIntent)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setStyle(MediaStyleNotificationHelper.MediaStyle(activeSession).setShowActionsInCompactView(0, 1, 2))
            .setLargeIcon(notificationArtwork)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPending)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                playPausePending
            )
            .addAction(android.R.drawable.ic_media_next, "Next", nextPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close App", closePending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            }
        } else {
            runCatching { startForeground(NOTIF_ID, notification) }
        }
    }

    private fun closeAppCompletely() {
        runCatching {
            wifiLock?.let { if (it.isHeld) it.release() }
            session?.player?.let {
                SessionStore.save(it)
                MediaSourceAudit.beforeMutation("stop:close_app", it)
                it.stop()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        exitProcess(0)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        val active = session
        Log.i(TAG, "onGetSession controller=${controllerInfo.packageName} available=${active != null}")
        return active
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val activeSession = session
        val player = activeSession?.player
        val browserConnected = activeSession?.connectedControllers?.isNotEmpty() == true
        val keepForPlaybackOrBrowser = player?.playWhenReady == true ||
            player?.isPlaying == true ||
            (player?.mediaItemCount ?: 0) > 0 ||
            browserConnected
        Log.i(TAG, "onTaskRemoved keepService=$keepForPlaybackOrBrowser browserConnected=$browserConnected queue=${player?.mediaItemCount ?: 0}")
        if (!keepForPlaybackOrBrowser) stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "MediaLibraryService destroyed sessionAvailable=${session != null}")
        main.removeCallbacks(lyricMetadataPoller)
        DriveRecapStore.serviceDestroyed(session?.player?.isPlaying == true)
        runCatching { unregisterReceiver(notifReceiver) }
        wifiLock?.let { if (it.isHeld) it.release() }
        PlayerHolder.get(this).let {
            SessionStore.save(it)
            it.removeListener(layoutListener)
        }
        Favorites.removeListener(favListener)
        getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(carBrowsePreferenceListener)
        AndroidAutoWarmupManager.shutdown()
        session?.player?.let { MediaSourceAudit.beforeMutation("release:media_library_session", it) }
        session?.release()
        session = null
        lyricsSessionPlayer = null
        io.shutdown()
        super.onDestroy()
    }

    private fun updateLayout() {
        val s = session ?: return
        val id = s.player.currentMediaItem?.mediaId
        val liked = id != null && Favorites.isLiked(id)
        val likeBtn = CommandButton.Builder()
            .setDisplayName(if (liked) "Unlike" else "Like")
            .setIconResId(if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            .setSessionCommand(LIKE_COMMAND)
            .build()
        val queueBtn = CommandButton.Builder()
            .setDisplayName("Queue next")
            .setIconResId(R.drawable.ic_queue_next)
            .setSessionCommand(QUEUE_NEXT_COMMAND)
            .build()
        val moodPreferences = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
        val savedMood = moodPreferences.getString("discovery_mood", null)
        val selectedMood = savedMood?.takeIf { SmartMoodEngine.profile(it) != null }
            ?: moodPreferences.getString("auto_mood_last", null)?.takeIf { it.isNotBlank() }
        val moodBtn = CommandButton.Builder()
            .setDisplayName(selectedMood?.let { "Mood: $it" } ?: "Choose a mood for me")
            .setIconResId(R.drawable.ic_mood_spark)
            .setSessionCommand(MOOD_FOR_ME_COMMAND)
            .build()
        s.setCustomLayout(ImmutableList.of(moodBtn, likeBtn, queueBtn))
    }

    private fun <T> bg(callbackName: String, block: () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        io.execute {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val value = block()
                when (value) {
                    is SessionResult -> Log.i(TAG, "SESSION_RESULT callback=$callbackName resultCode=${value.resultCode} " +
                        "status=${if (value.resultCode == SessionResult.RESULT_SUCCESS) "SUCCESS" else "ERROR"}")
                    is MediaSession.MediaItemsWithStartPosition -> Log.i(TAG, "SESSION_RESULT callback=$callbackName status=SUCCESS readyMediaItems")
                }
                future.set(value)
            } catch (error: Throwable) {
                Log.e(TAG, "SESSION_CALLBACK callback=$callbackName status=EXCEPTION type=${error.javaClass.simpleName} message=${error.message}", error)
                future.setException(error)
            } finally {
                Log.d(TAG, "$callbackName durationMs=${SystemClock.elapsedRealtime() - startedAt}")
            }
        }
        return future
    }

    private fun <T> logLibraryResult(callbackName: String, result: LibraryResult<T>): LibraryResult<T> {
        val status = if (result.resultCode == LibraryResult.RESULT_SUCCESS) "SUCCESS" else "ERROR"
        Log.i(TAG, "LIBRARY_RESULT callback=$callbackName resultCode=${result.resultCode} status=$status hasValue=${result.value != null}")
        return result
    }

    private inline fun <T> traceCallback(callbackName: String, block: () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            return block()
        } catch (error: Throwable) {
            Log.e(TAG, "$callbackName failed", error)
            throw error
        } finally {
            Log.d(TAG, "$callbackName durationMs=${SystemClock.elapsedRealtime() - startedAt}")
        }
    }
    private fun register(items: List<MediaItem>): List<MediaItem> {
        val playable = items.filter { it.mediaMetadata.isPlayable == true }
        playable.forEach {
            index[it.mediaId] = it
            siblings[it.mediaId] = playable
        }
        return items
    }

    private fun songItems(songs: List<SongItemClean>): List<MediaItem> {
        val filterMature = getSharedPreferences("vybe_app_settings", MODE_PRIVATE).getBoolean("filter_mature_content", true)
        val visibleSongs = songs.filter { !filterMature || !it.isExplicit }
        val items = visibleSongs.map { s ->
            if (s.videoId.isNotEmpty()) {
                s.toMediaItem()
            } else {
                folderItem("pl:${s.playlistId}", s.title, s.artist, s.coverUrl)
            }
        }
        return register(items)
    }

    private fun radioMediaItems(stations: List<RadioStation>): List<MediaItem> = register(
        stations.map { station ->
            val song = station.asSong()
            val metadata = MediaMetadata.Builder()
                .setTitle(station.name)
                .setArtist(song.artist)
                .setAlbumTitle("Internet Radio")
                .setSubtitle(station.country.ifBlank { "Live radio" })
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            if (station.favicon.isNotBlank()) metadata.setArtworkUri(Uri.parse(station.favicon))
            MediaItem.Builder()
                .setMediaId("radio:" + station.id)
                .setUri(station.streamUrl)
                .setMediaMetadata(metadata.build())
                .build()
        }
    )

    private fun radioBrowseQuickLinks(): List<MediaItem> {
        val localStations = (
            RadioStationStore.load(this, "radio_favorites") +
                RadioStationStore.load(this, "radio_recent_stations") +
                RadioStationStore.load(this, "radio_custom_stations")
            ).distinctBy { it.id }
        return radioMediaItems(localStations).ifEmpty {
            listOf(folderItem("radio:no_stations", "No stations available", "Check your connection and try browsing again"))
        }
    }

    private fun carHomeShelves(): List<HomeShelf> {
        val cached = HomeCache.shelves
        if (cached.isEmpty()) refreshHomeCatalogAsync()
        return cached
    }

    private fun refreshHomeCatalogAsync() {
        if (!homeRefreshInProgress.compareAndSet(false, true)) return
        if (!allowCatalogRefresh("home", 30_000L)) {
            homeRefreshInProgress.set(false)
            return
        }
        io.execute {
            try {
                val loaded = runCatching { YtRepo.fetchHomeCatalog().shelves }
                    .onFailure { Log.e(TAG, "Android Auto home catalog refresh failed", it) }
                    .getOrDefault(emptyList())
                if (loaded.isNotEmpty()) {
                    HomeCache.set(loaded)
                    val refreshedParents = (
                        listOf(ROOT, TAB_HOME, TAB_BROWSE, TAB_PICKS, TAB_CONTINUE, TAB_RECENT, TAB_TRENDING, TAB_HEARD_SHORTS, TAB_LISTEN_AGAIN, TAB_SHELVES, TAB_PLAYLISTS, TAB_SONGS, TAB_ALBUMS, TAB_ARTISTS) +
                            loaded.indices.map { "shelf:$it" }
                        ).distinct()
                    val refreshedCounts = refreshedParents
                        .mapNotNull { parentId ->
                            val safe = cacheChildren(parentId, childrenOf(parentId))
                            safe.size.takeIf { it > 0 }?.let { parentId to it }
                        }
                    main.post {
                        val activeSession = session ?: return@post
                        refreshedCounts.forEach { (parentId, count) ->
                            activeSession.notifyChildrenChanged(parentId, count, null)
                        }
                    }
                }
            } finally {
                homeRefreshInProgress.set(false)
            }
        }
    }
    private fun allowCatalogRefresh(key: String, minimumIntervalMs: Long = 60_000L): Boolean = synchronized(lastCatalogRefreshAt) {
        val now = System.currentTimeMillis()
        val previous = lastCatalogRefreshAt[key] ?: 0L
        if (now - previous < minimumIntervalMs) false else {
            lastCatalogRefreshAt[key] = now
            true
        }
    }

    private fun validMediaItems(parentId: String, items: List<MediaItem>): List<MediaItem> {
        val seenIds = HashSet<String>()
        return items.mapIndexedNotNull { position, item ->
        val id = item.mediaId.trim()
        if (id.isBlank()) {
            Log.w(TAG, "Dropping Android Auto item with empty mediaId parent=$parentId position=$position")
            return@mapIndexedNotNull null
        }
        if (!seenIds.add(id)) {
            Log.w(TAG, "Dropping duplicate Android Auto mediaId parent=$parentId position=$position mediaId=$id")
            return@mapIndexedNotNull null
        }
        val playable = item.mediaMetadata.isPlayable == true
        val browsable = item.mediaMetadata.isBrowsable == true
        if (!playable && !browsable) {
            Log.w(TAG, "Dropping non-playable, non-browsable Android Auto item parent=$parentId mediaId=$id")
            return@mapIndexedNotNull null
        }
        val title = item.mediaMetadata.title?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (playable) "Untitled track" else "Untitled section"
        // Android Auto/AAOS browse artwork must be a locally resolvable URI. Remote URLs
        // and inline bitmaps are not reliable for browse rows (and can bloat Binder replies).
        // Always give browsable folders a local resource icon, including stale Room rows
        // written by older versions that did not persist artwork bytes.
        val sourceArtworkUri = item.mediaMetadata.artworkUri?.takeIf {
            it.scheme?.lowercase() in setOf("content", "android.resource")
        }
        val artworkUri = if (browsable) sourceArtworkUri ?: browseCategoryArtworkUri(id, title)
            else item.mediaMetadata.artworkUri?.takeIf {
                it.scheme?.lowercase() in setOf("http", "https", "content", "android.resource")
            }
        val artworkData = item.mediaMetadata.artworkData.takeUnless { browsable }
        val artworkDataType = item.mediaMetadata.artworkDataType.takeUnless { browsable }
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle())
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(item.mediaMetadata.subtitle)
            .setArtist(item.mediaMetadata.artist)
            .setAlbumTitle(item.mediaMetadata.albumTitle)
            .setIsPlayable(playable)
            .setIsBrowsable(browsable)
            .setMediaType(if (browsable) MediaMetadata.MEDIA_TYPE_MIXED else MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
        if (browsable) metadata.setFolderType(item.mediaMetadata.folderType ?: MediaMetadata.FOLDER_TYPE_MIXED)
        artworkUri?.let(metadata::setArtworkUri)
        artworkData?.let { metadata.setArtworkData(it, artworkDataType) }
        val normalized = item.buildUpon().setMediaId(id).setMediaMetadata(metadata.build()).build()
        val styled = AndroidAutoUiModule.applyChildStyle(normalized, parentId)
        AndroidAutoPlaybackBridge.validate(styled, "browse:$parentId")
            ?: return@mapIndexedNotNull null
        }
    }

    private fun fallbackChildren(parentId: String): List<MediaItem> {
        val fallbackSongs = localLibrarySongs().map { it.toMediaItem() }
        val localShelves = HomeCache.shelves
        if (parentId.startsWith("tripmix:")) {
            val (mood, durationMinutes, _) = tripMixParameters(parentId)
            val prefs = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
            val profile = ProfileEngine.load(prefs)
            val local = AndroidAutoMoodRecommendationAdapter.recommend(
                this, mood, profile, MoodTrackFeedbackStore.hiddenIds(prefs, mood),
                localCandidates = localLibrarySongs(), includeRemoteCandidates = false
            )
            return songItems(selectTripLength(local, durationMinutes))
        }
        val result = when (parentId) {
            ROOT -> carRootItems()
            TAB_HOME -> personalizedCarHomeItems()
            "journeys:home" -> listOf(
                moodCard("Night Drive", "🌃 Night Drive Journey", "A familiar night mix, shaped by your artists"),
                moodCard("Road Trip", "🛣️ Road Trip Journey", "A longer mix shaped by your listening"),
                moodCard("Commute", "🚗 Commute Journey", "A quick mix for the drive"),
                moodCard("Discover", "✨ Discovery Journey", "Fresh picks from KILO")
            )
            TAB_SEARCH -> {
                val recent = SearchAnalytics.recentSearches(limit = 6)
                recent.map { query -> carTile("search:query:${Uri.encode(query)}", query, "Recent search", AndroidAutoR.drawable.ic_auto_recent) } +
                    listOf("New music", "Night Drive", "Tamil Hits", "Hip-Hop", "Throwback Hits", "Workout mix")
                        .filterNot { it in recent }
                        .map { query -> carTile("search:query:${Uri.encode(query)}", query, "Popular search", AndroidAutoR.drawable.ic_auto_search) }
            }
            TAB_BROWSE -> browseCategories()
            TAB_RADIO -> listOf(
                carTile(TAB_RADIO_BROWSE, "Browse", "Find live stations", AndroidAutoR.drawable.ic_auto_radio),
                carTile(TAB_RADIO_FAVORITES, "Favorites", "Stations you saved", AndroidAutoR.drawable.ic_auto_liked),
                carTile(TAB_RADIO_RECENT, "Recent", "Stations you played recently", AndroidAutoR.drawable.ic_auto_recent),
                carTile(TAB_RADIO_CUSTOM, "My Stations", "Your added stations", AndroidAutoR.drawable.ic_auto_library)
            )
            TAB_LIBRARY -> listOf(
                carTile(TAB_LIKED, "Liked Songs", "Your saved tracks", AndroidAutoR.drawable.ic_auto_liked),
                carTile(TAB_PLAYLISTS, "Playlists", "Your saved playlists", AndroidAutoR.drawable.ic_auto_playlists),
                carTile(TAB_ARTISTS, "Followed Artists", "Music from artists you follow", AndroidAutoR.drawable.ic_auto_library)
            )
            TAB_PICKS -> quickPicksItems()
            TAB_RECENT, TAB_CONTINUE, TAB_LISTEN_AGAIN, TAB_TRENDING, TAB_HEARD_SHORTS, TAB_SONGS ->
                fallbackSongs.ifEmpty { listOf(folderItem(TAB_BROWSE, "No listening history yet", "Browse moods and Quick Picks")) }
            TAB_LIKED -> Favorites.songs().map { it.toMediaItem() }.ifEmpty {
                listOf(folderItem(TAB_PICKS, "No liked songs yet", "Explore Quick Picks and save favorites"))
            }
            TAB_MOODS, TAB_GENRES, TAB_ARTISTS -> childrenOf(parentId)
            TAB_ALBUMS -> childrenOf(parentId)
            TAB_PLAYLISTS -> savedPlaylists().map { playlist ->
                folderItem("savedpl:${playlist.id}", playlist.name, "${playlist.songs.size} songs", playlist.songs.firstOrNull()?.coverUrl)
            }
            TAB_RADIO_BROWSE -> radioMediaItems(
                (RadioStationStore.load(this, "radio_favorites") +
                    RadioStationStore.load(this, "radio_recent_stations") +
                    RadioStationStore.load(this, "radio_custom_stations")).distinctBy { it.id }
            ).ifEmpty { radioBrowseQuickLinks() }
            TAB_OFFLINE -> offlineBrowseCategories()
            else -> when {
                parentId.startsWith("home:") || parentId.startsWith("quickpicks:after:") -> childrenOf(parentId)
                parentId in setOf("radio:genres", "radio:moods", "radio:regional", "radio:artists", "offline:songs", "offline:playlists", "offline:albums", "offline:mixes", "offline:storage") -> childrenOf(parentId)
                parentId == "radio:song" -> songItems(SessionStore.recentSongs().ifEmpty { cachedHomeSongs() })
                parentId == "radio:trending" -> songItems(matchingHomeShelf("trend", "viral", "top hits", "popular")?.songs.orEmpty())
                parentId.startsWith("offline:playlist:") -> childrenOf(parentId)
                parentId.startsWith("offline:album:") -> childrenOf(parentId)
                parentId.startsWith("search:query:") -> {
                    val query = Uri.decode(parentId.removePrefix("search:query:")).lowercase(Locale.ROOT)
                    songItems(cachedHomeSongs().filter { "${it.title} ${it.artist} ${it.album}".lowercase(Locale.ROOT).contains(query) })
                }
                parentId == "mood:auto" -> emptyList()
                parentId.startsWith("mood:") || parentId.startsWith("moodradio:") -> emptyList()
                parentId.startsWith("genre:") ->
                    songItems(matchingCachedCategorySongs(parentId))
                parentId.startsWith("artist:") -> fallbackSongs
                parentId.startsWith("savedpl:") -> savedPlaylists()
                    .firstOrNull { it.id == parentId.removePrefix("savedpl:") }?.songs?.map { it.toMediaItem() }.orEmpty()
                parentId.startsWith("shelf:") -> {
                    val index = parentId.removePrefix("shelf:").toIntOrNull() ?: -1
                    localShelves.getOrNull(index)?.songs?.map { it.toMediaItem() }.orEmpty().ifEmpty { fallbackSongs }
                }
                parentId.startsWith("pl:") -> fallbackSongs
                parentId.startsWith("radio:") -> emptyList()
                else -> emptyList()
            }
        }
        return validMediaItems(parentId, result)
    }

    private fun matchingCachedCategorySongs(parentId: String): List<SongItemClean> {
        val candidates = (cachedHomeSongs() + SessionStore.recentSongs() + Favorites.songs())
            .filter { it.videoId.isNotBlank() || it.id.isNotBlank() }
            .distinctBy { it.videoId.ifBlank { it.id } }
        val category = when {
            parentId.startsWith("mood:") -> Uri.decode(parentId.removePrefix("mood:"))
            parentId.startsWith("moodradio:") -> Uri.decode(parentId.removePrefix("moodradio:"))
            else -> Uri.decode(parentId.removePrefix("genre:"))
        }
        val terms = (if (parentId.startsWith("genre:")) listOf(category) else {
            listOf(category) + SmartMoodEngine.profile(category)?.genres.orEmpty()
        }).map { it.lowercase(Locale.ROOT) }.filter(String::isNotBlank)
        if (terms.isEmpty()) return emptyList()
        return candidates.filter { song ->
            val searchable = "${song.title} ${song.artist} ${song.album}".lowercase(Locale.ROOT)
            terms.any(searchable::contains)
        }
    }

    private fun cacheChildren(parentId: String, items: List<MediaItem>): List<MediaItem> {
        val safe = validMediaItems(parentId, items)
        if (isCategorySongParent(parentId)) {
            BrowseTreeCache.put(parentId, safe.filterNot { it.mediaId.startsWith("info:") })
        }
        if (!isVolatileBrowseParent(parentId)) {
            BrowseTreeCache.put(parentId, safe.filterNot { it.mediaId.startsWith("info:") })
            runCatching {
                // Empty-state rows stay transient so new content can replace them later.
                AutoBrowseCache.write(this, parentId, safe.filterNot { it.mediaId.startsWith("info:") })
            }.onFailure { Log.e(TAG, "Could not cache Android Auto section $parentId", it) }
        }
        return safe
    }

    private fun isVolatileBrowseParent(parentId: String): Boolean =
        parentId in setOf(
            ROOT, TAB_HOME, TAB_BROWSE, TAB_SEARCH, TAB_RECENT, TAB_CONTINUE, TAB_PICKS, TAB_LIKED, TAB_PLAYLISTS, TAB_OFFLINE,
            TAB_RADIO_FAVORITES, TAB_RADIO_RECENT, TAB_RADIO_CUSTOM,
            TAB_GENRES, TAB_MOODS, TAB_ARTISTS, TAB_ALBUMS, TAB_SONGS
        ) || parentId.startsWith("home:") || parentId.startsWith("quickpicks:after:") || parentId.startsWith("savedpl:") || parentId.startsWith("genre:") ||
            parentId.startsWith("mood:") || parentId.startsWith("moodradio:") || parentId.startsWith("artist:") || parentId.startsWith("radio:")

    private fun isCategorySongParent(parentId: String): Boolean =
            parentId.startsWith("mood:") ||
            parentId.startsWith("moodradio:") || parentId.startsWith("genre:")

    private fun requestBrowseGeneration(parentId: String) {
        // Only generate children for sections whose first page actually needs remote data.
        // Rebuilding static category lists and notifying them causes Android Auto to ask
        // for the same page again, creating a browse/notification loop (notably for Moods).
        val needsBackgroundGeneration =
            parentId.startsWith("mood:") ||
                parentId.startsWith("moodradio:") ||
                parentId.startsWith("genre:") ||
                parentId.startsWith("artist:") ||
                parentId.startsWith("tripmix:") ||
                parentId.startsWith("discovery:") ||
                parentId.startsWith("search:query:") ||
                parentId == "radio:song" ||
                parentId == "radio:trending" ||
                parentId.startsWith("home:radio_")
        if (!needsBackgroundGeneration) return
        if (!pendingBrowseBuilds.add(parentId)) return
        io.execute {
            try {
                val previousIds = BrowseTreeCache.get(parentId).map(MediaItem::mediaId)
                val generated = runCatching { childrenOf(parentId) }
                    .onFailure { Log.e(TAG, "Could not build Android Auto section $parentId", it) }
                    .getOrDefault(emptyList())
                val safe = cacheChildren(parentId, generated)
                val changed = safe.map(MediaItem::mediaId) != previousIds
                if (isCategorySongParent(parentId)) {
                    Log.i(TAG, "AA_CATEGORY_RESULTS parent=$parentId count=${safe.size} changed=$changed")
                }
                if (safe.isNotEmpty() && changed) main.post {
                    session?.notifyChildrenChanged(parentId, safe.size, null)
                }
            } finally {
                pendingBrowseBuilds.remove(parentId)
            }
        }
    }

    private fun ensureMoodShortcut(parentId: String, items: List<MediaItem>): List<MediaItem> {
        if (parentId != TAB_MOODS || items.any { it.mediaId == "mood:auto" }) return items
        return listOf(setMyMoodCard()) + items
    }

    private fun emptyBrowseFallback(parentId: String): MediaItem {
        val target = if (parentId == TAB_BROWSE) TAB_MOODS else TAB_BROWSE
        val artwork = if (target == TAB_MOODS) AndroidAutoR.drawable.ic_auto_mood else AndroidAutoR.drawable.ic_auto_library
        return folderItem(
            id = target,
            title = "Nothing here yet",
            subtitle = if (target == TAB_MOODS) "Choose a mood and start listening" else "Browse moods and Quick Picks",
            art = autoArtworkUri(artwork).toString()
        )
    }

    private fun withoutBrowseCycles(parentId: String, items: List<MediaItem>): List<MediaItem> =
        items.filterNot { item ->
            val isCycle = item.mediaId == parentId || item.mediaId == ROOT
            if (isCycle) Log.e(TAG, "Dropping recursive Android Auto browse item parent=$parentId child=${item.mediaId}")
            isCycle
        }

    private fun childrenFromCache(parentId: String): List<MediaItem> {
        if (!isVolatileBrowseParent(parentId) || isCategorySongParent(parentId)) {
            val memory = BrowseTreeCache.get(parentId).filterNot { it.mediaId.startsWith("info:") }
            if (memory.isNotEmpty()) {
                val validMemory = withoutBrowseCycles(parentId, validMediaItems(parentId, ensureMoodShortcut(parentId, memory)))
                if (validMemory.isNotEmpty()) {
                    requestRemoteRefresh(parentId)
                    return register(validMemory)
                }
            }
        }
        val cached = if (isVolatileBrowseParent(parentId)) emptyList() else runCatching {
            AutoBrowseCache.read(this, parentId)
        }.onFailure { Log.e(TAG, "Could not read cached Android Auto section $parentId", it) }
            .getOrDefault(emptyList())
            .filterNot { it.mediaId.startsWith("info:") }
        if (cached.isNotEmpty()) {
            val validCached = withoutBrowseCycles(parentId, validMediaItems(parentId, ensureMoodShortcut(parentId, cached)))
            if (validCached.isNotEmpty()) {
                BrowseTreeCache.put(parentId, validCached)
                runCatching { AutoBrowseCache.write(this, parentId, validCached) }
                    .onFailure { Log.w(TAG, "Could not update Android Auto shortcut cache parent=$parentId", it) }
                requestRemoteRefresh(parentId)
                return register(validCached)
            }
        }

        // Return local data immediately. Catalog/search work is queued off the browse callback.
        val fallback = withoutBrowseCycles(parentId, fallbackChildren(parentId))
        if (fallback.isEmpty() && isCategorySongParent(parentId)) {
            // Do not insert a fake "Nothing here" folder that drivers must open again.
            // Return a successful empty page while the category search runs in the
            // background; the cache is then published to Android Auto once it is ready.
            requestBrowseGeneration(parentId)
            Log.i(TAG, "AA_CATEGORY_RESULTS parent=$parentId count=0 status=LOADING")
            return emptyList()
        }
        val safe = fallback.ifEmpty { listOf(emptyBrowseFallback(parentId)) }
        if (fallback.isNotEmpty() && isCategorySongParent(parentId)) BrowseTreeCache.put(parentId, fallback)
        else if (safe.isNotEmpty() && !isVolatileBrowseParent(parentId)) BrowseTreeCache.put(parentId, safe)
        requestBrowseGeneration(parentId)
        requestRemoteRefresh(parentId)
        if (safe.isEmpty()) Log.i(TAG, "Browse section is empty parent=$parentId; returning an empty list")
        return register(safe)
    }

    private fun isSignedIn(): Boolean = YtRepo.userAuthCookie.contains("SAPISID")

    private fun downloadedMediaItems(): List<MediaItem> = runCatching {
        val prefs = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
        val downloads = JSONObject(prefs.getString("downloaded_song_uris", "{}") ?: "{}")
        val songs = JSONArray(prefs.getString("downloaded_songs", "[]") ?: "[]")
        (0 until songs.length()).mapNotNull { index ->
            val song = songs.optJSONObject(index) ?: return@mapNotNull null
            val id = song.optString("id").ifBlank { song.optString("videoId") }
            val uri = downloads.optString(id).takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val videoId = song.optString("videoId", id).ifBlank { id }
            val metadata = MediaMetadata.Builder()
                .setTitle(song.optString("title", "Downloaded track"))
                .setArtist(song.optString("artist", "Unknown artist"))
                .setAlbumTitle(song.optString("album", ""))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            song.optString("coverUrl").takeIf { it.isNotBlank() }?.let { metadata.setArtworkUri(Uri.parse(it)) }
            MediaItem.Builder().setMediaId(videoId).setUri(Uri.parse(uri)).setMediaMetadata(metadata.build()).build()
        }
    }.onFailure { Log.e(TAG, "Could not load downloaded Android Auto songs", it) }.getOrDefault(emptyList())

    private fun offlineBrowseCategories(): List<MediaItem> {
        val downloads = downloadedMediaItems()
        val downloadedIds = downloads.mapTo(mutableSetOf()) { it.mediaId }
        val downloadedSongs = downloads.mapIndexed { index, item -> item.toSong(index) }
        val playlists = savedPlaylists().filter { playlist ->
            playlist.songs.any { it.videoId in downloadedIds || it.id in downloadedIds }
        }
        val mixes = playlists.filter { it.name.startsWith("Road Mix", ignoreCase = true) }
        val albumCount = downloadedSongs.map { it.album.trim() }.filter(String::isNotBlank).distinct().size
        val totalMinutes = downloadedSongs.sumOf { it.durationMs.coerceAtLeast(0L) } / 60_000L
        return listOf(
            carTile("offline:songs", "Downloaded Songs", "${downloads.size} tracks · ${totalMinutes} minutes", AndroidAutoR.drawable.ic_auto_downloads),
            carTile("offline:playlists", "Downloaded Playlists", "${playlists.size} playlists available offline", AndroidAutoR.drawable.ic_auto_playlists),
            carTile("offline:albums", "Downloaded Albums", "$albumCount albums available offline", AndroidAutoR.drawable.ic_auto_playlists),
            carTile("offline:mixes", "Offline Mixes", "${mixes.size} saved Road Mixes", AndroidAutoR.drawable.ic_auto_mood),
            carTile("offline:storage", "Storage Overview", "$totalMinutes minutes of music across ${downloads.size} tracks", AndroidAutoR.drawable.ic_auto_downloads)
        )
    }

    private fun requestRemoteRefresh(parentId: String) {
        val key = when {
            parentId == TAB_HOME || parentId == TAB_PICKS || parentId == TAB_TRENDING ||
                parentId == TAB_HEARD_SHORTS || parentId == TAB_SHELVES || parentId == TAB_PLAYLISTS ||
                parentId == TAB_BROWSE || parentId == TAB_LISTEN_AGAIN -> "home"
            parentId == TAB_RADIO_BROWSE -> "radio"
            parentId.startsWith("pl:") -> parentId
            else -> return
        }
        if (key == "home") {
            refreshHomeCatalogAsync()
            return
        }
        if (!pendingCatalogRefreshes.add(key)) return
        if (!allowCatalogRefresh(key)) {
            pendingCatalogRefreshes.remove(key)
            return
        }
        io.execute {
            try {
                val loaded = when {
                    key == "radio" -> radioMediaItems(RadioBrowser.stations())
                    key.startsWith("pl:") -> YtRepo.fetchPlaylistTracks(key.removePrefix("pl:")).map { it.toMediaItem() }
                    else -> emptyList()
                }
                val safe = cacheChildren(parentId, loaded)
                if (safe.isNotEmpty()) main.post {
                    session?.notifyChildrenChanged(parentId, safe.size, null)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Android Auto catalog refresh failed for $parentId", error)
            } finally {
                pendingCatalogRefreshes.remove(key)
            }
        }
    }
    private fun matchingHomeShelf(vararg terms: String): HomeShelf? {
        val normalizedTerms = terms.map { it.lowercase() }
        return carHomeShelves().firstOrNull { shelf ->
            val title = shelf.title.lowercase()
            normalizedTerms.any { term -> title.contains(term) }
        }
    }

    private fun browseCategories(): List<MediaItem> {
        return listOf(
            carTile(TAB_MOODS, "Moods", "Pick a feeling and start a mix", AndroidAutoR.drawable.ic_auto_mood),
            carTile(TAB_GENRES, "Genres", "Explore sounds and languages", AndroidAutoR.drawable.ic_auto_library),
            carTile(TAB_OFFLINE, "Downloads", "Music saved for offline listening", AndroidAutoR.drawable.ic_auto_downloads),
            carTile(TAB_ARTISTS, "Artists", "Music from artists you follow", AndroidAutoR.drawable.ic_auto_artists)
        )
    }

    private fun savedPlaylists(): List<SavedPlaylist> = runCatching {
        val prefs = getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
        val array = JSONArray(prefs.getString("saved_playlists", "[]"))
        (0 until array.length()).mapNotNull playlistLoop@ { playlistIndex ->
            val entry = array.optJSONObject(playlistIndex) ?: return@playlistLoop null
            val songsArray = entry.optJSONArray("songs") ?: JSONArray()
            val songs = (0 until songsArray.length()).mapNotNull songLoop@ { songIndex ->
                val song = songsArray.optJSONObject(songIndex) ?: return@songLoop null
                val id = song.optString("id")
                if (id.isBlank()) return@songLoop null
                SongItemClean(
                    id = id,
                    videoId = song.optString("videoId", id),
                    title = song.optString("title", "Unknown"),
                    artist = song.optString("artist", "Unknown"),
                    album = song.optString("album", "YouTube Music"),
                    coverUrl = song.optString("coverUrl"),
                    durationMs = song.optLong("durationMs", 210000L)
                )
            }
            SavedPlaylist(entry.optString("id"), entry.optString("name", "Playlist"), songs)
        }
    }.getOrDefault(emptyList())

    /** Followed artists lead; artists found in saved, recent, downloaded, and cached music follow. */
    private fun androidAutoArtistNames(): List<String> {
        val followed = ProfileEngine.load(getSharedPreferences("vybe_app_settings", MODE_PRIVATE)).followedArtists
        val heard = localLibrarySongs().map(SongItemClean::artist)
        return (followed + heard)
            .asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) && !it.equals("various artists", ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(50)
            .toList()
    }

    /** Prefer the artist's actual YouTube Music catalog, with a strictly artist-matched search fallback. */
    private fun androidAutoArtistSongs(name: String): List<SongItemClean> {
        if (name.isBlank()) return emptyList()
        val artist = runCatching {
            val matches = YtRepo.searchArtists(name)
            matches.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: matches.firstOrNull { it.name.contains(name, ignoreCase = true) || name.contains(it.name, ignoreCase = true) }
                ?: matches.firstOrNull()
        }.onFailure { Log.w(TAG, "Android Auto artist lookup failed artist=$name", it) }.getOrNull()
        val catalogSongs = artist?.let { matched ->
            runCatching { YtRepo.fetchArtistCatalog(matched).third }
                .onFailure { Log.w(TAG, "Android Auto artist catalog failed artist=$name", it) }
                .getOrDefault(emptyList())
        }.orEmpty().filter { it.videoId.isNotBlank() }
        if (catalogSongs.isNotEmpty()) return catalogSongs.distinctBy(SongItemClean::videoId)

        return runCatching { YtRepo.searchSongs("songs by $name") }
            .onFailure { Log.w(TAG, "Android Auto artist song search failed artist=$name", it) }
            .getOrDefault(emptyList())
            .filter { song ->
                song.videoId.isNotBlank() && song.artist.contains(artist?.name ?: name, ignoreCase = true)
            }
            .distinctBy(SongItemClean::videoId)
    }

    private fun carMoodSuggestion(): MoodSuggestion = suggestMoodForConditions(
        Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        MoodContext.cachedTemperatureC(this)
    )

    private fun activeCarMood(): String {
        val saved = getSharedPreferences("vybe_app_settings", MODE_PRIVATE).getString("discovery_mood", null)
        return saved?.takeIf { SmartMoodEngine.profile(it) != null } ?: carMoodSuggestion().mood
    }

    /** Builds the Android Auto ride queue off the browse callback thread. */
    private fun buildRideMix(mood: String, durationMinutes: Int, _destination: String): List<SongItemClean> {
        val prefs = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
        val profile = ProfileEngine.load(prefs)
        val excluded = MoodTrackFeedbackStore.hiddenIds(prefs, mood)
        val ranked = AndroidAutoMoodRecommendationAdapter.recommend(
            context = this,
            mood = mood,
            profile = profile,
            excludedIds = excluded,
            localCandidates = localLibrarySongs()
        )
        return selectTripLength(ranked, durationMinutes)
    }

    private fun selectTripLength(songs: List<SongItemClean>, durationMinutes: Int): List<SongItemClean> {
        val targetMs = durationMinutes.coerceIn(15, 999 * 60).toLong() * 60_000L
        val selected = mutableListOf<SongItemClean>()
        var durationMs = 0L
        for (song in songs) {
            if (song.videoId.isBlank() || selected.any { it.videoId == song.videoId }) continue
            selected += song
            durationMs += song.durationMs.coerceAtLeast(0L)
            if (durationMs >= targetMs) break
        }
        return selected
    }

    private fun carRootItems(): List<MediaItem> {
        // Hydrate the Android Auto catalog in the background as soon as the car opens KILO.
        carHomeShelves()
        // Android Auto supports at most four root navigation tabs. Keep Search on the
        // host's native search action so Library remains directly visible as a tab.
        return listOf(
            carTile(TAB_HOME, "Home", "Continue listening and picks for you", AndroidAutoR.drawable.ic_auto_continue),
            carTile(TAB_BROWSE, "Browse", "Moods, genres, library and downloads", AndroidAutoR.drawable.ic_auto_library),
            carTile(TAB_RADIO, "Radio", "Your stations and music mixes", AndroidAutoR.drawable.ic_auto_radio),
            carTile(TAB_LIBRARY, "Library", "Liked songs, playlists and followed artists", AndroidAutoR.drawable.ic_auto_library)
        )
    }

    private fun personalizedCarHomeItems(): List<MediaItem> {
        return buildList {
            add(continueListeningTile())
            add(carTile(TAB_PICKS, "Quick Picks", "Songs selected for you", AndroidAutoR.drawable.ic_auto_continue))
            add(carTile(TAB_RECENT, "Recently Played", "Return to a recent listen", AndroidAutoR.drawable.ic_auto_recent))
            add(carTile("home:made_for_you", "Made For You", "Personal recommendations", AndroidAutoR.drawable.ic_auto_mood))
            add(carTile("home:recent_mixes", "Recent Mixes", "Your saved and recent mixes", AndroidAutoR.drawable.ic_auto_playlists))
            add(carTile(TAB_ARTISTS, "Your Favourite Artists", "Music from artists you follow", AndroidAutoR.drawable.ic_auto_library))
            add(carTile(TAB_TRENDING, "Trending For You", "Popular picks shaped by your taste", AndroidAutoR.drawable.ic_auto_recent))
        }
    }

    private fun continueListeningTile(): MediaItem {
        val current = currentTrackSnapshot
        val recent = runCatching { SessionStore.recentSongs().firstOrNull() }.getOrNull()
        val title = current?.mediaMetadata?.title?.toString()
            ?.takeIf(String::isNotBlank)
            ?: recent?.title?.takeIf(String::isNotBlank)
        val artwork = current?.mediaMetadata?.artworkUri?.toString()
            ?.takeIf(String::isNotBlank)
            ?: recent?.coverUrl?.takeIf(String::isNotBlank)
        return if (artwork != null) {
            folderItem(TAB_CONTINUE, "Continue Listening", title ?: "Pick up where you left off", artwork)
        } else {
            carTile(TAB_CONTINUE, "Continue Listening", title ?: "Pick up where you left off", AndroidAutoR.drawable.ic_auto_continue)
        }
    }

    private fun moodEmoji(mood: String): String = when (mood.lowercase()) {
        "night drive" -> "🌃"
        "commute" -> "🚗"
        "road trip" -> "🛣️"
        "workout" -> "💪"
        "focus" -> "🧠"
        "relax" -> "😌"
        "gaming" -> "🎮"
        "party" -> "🎉"
        "rain drive" -> "🌧️"
        "romantic" -> "❤️"
        "sleep" -> "😴"
        "happy" -> "😊"
        "sad" -> "😔"
        "coffee vibes" -> "☕"
        else -> "🎧"
    }

    private fun autoArtworkUri(resourceId: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(resources.getResourcePackageName(resourceId))
        .appendPath(resources.getResourceTypeName(resourceId))
        .appendPath(resources.getResourceEntryName(resourceId))
        .build()

    private fun browseCategoryArtworkUri(mediaId: String, title: String): Uri {
        return ArtworkCacheManager.artworkUri(this, ArtworkCacheManager.keyFor(mediaId, title))
    }

    private fun carTile(id: String, title: String, subtitle: String, artwork: Int): MediaItem =
        folderItem(
            id,
            title,
            subtitle,
            art = ArtworkCacheManager.artworkUri(this, ArtworkCacheManager.keyFor(id, title)).toString()
        )

    private fun moodCard(mood: String, title: String = "${moodEmoji(mood)} $mood", subtitle: String = "Music for $mood"): MediaItem =
        folderItem(
            id = "mood:${Uri.encode(mood)}",
            title = title,
            subtitle = subtitle,
            art = ArtworkCacheManager.artworkUri(this, "moods").toString(),
            childStyle = AUTO_STYLE_GRID
        )

    private fun rideMixParentId(mood: String, durationMinutes: Int, destination: String): String =
        "tripmix:${Uri.encode(mood)}:${durationMinutes}:${Uri.encode(destination)}"

    private fun rideMixTile(prefs: android.content.SharedPreferences): MediaItem {
        val mood = prefs.getString("trip_builder_mood", "Road Trip")?.takeIf(String::isNotBlank) ?: "Road Trip"
        val duration = prefs.getInt("trip_builder_duration_minutes", 60).coerceIn(15, 999 * 60)
        val durationLabel = if (duration % 60 == 0) "${duration / 60} hr" else "$duration min"
        return carTile(
            rideMixParentId(mood, duration, ""),
            "Ride Mix",
            "$durationLabel · $mood · personalized mix",
            AndroidAutoR.drawable.ic_auto_mood
        )
    }

    private fun tripMixParameters(parentId: String): Triple<String, Int, String> {
        val parts = parentId.split(":", limit = 4)
        val mood = parts.getOrNull(1)?.let(Uri::decode)?.takeIf(String::isNotBlank) ?: "Road Trip"
        val duration = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(15, 999 * 60) ?: 60
        val destination = parts.getOrNull(3)?.let(Uri::decode).orEmpty()
        return Triple(mood, duration, destination)
    }

    private fun setMyMoodCard(suggestion: MoodSuggestion = carMoodSuggestion()): MediaItem =
        folderItem(
            id = "mood:auto",
            title = "Set My Mood",
            subtitle = "Auto-pick ${suggestion.mood} · ${suggestion.reason}",
            childStyle = AUTO_STYLE_GRID,
            art = autoArtworkUri(AndroidAutoR.drawable.ic_auto_mood).toString()
        )

    private fun applyCarMoodSelection(mood: String, reason: String = "Selected in Android Auto") {
        val prefs = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
        val profile = SmartMoodEngine.profile(mood)
        val previous = prefs.getString("auto_mood_last", null)
        prefs.edit()
            .putString("discovery_mood", mood)
            .putString("auto_mood_previous", previous?.takeIf { it != mood } ?: prefs.getString("auto_mood_previous", null))
            .putString("auto_mood_last", mood)
            .putString("auto_mood_reason", reason)
            .putString("active_mood_theme", profile?.themePreset.orEmpty())
            .putString("active_mood_android_auto_profile", profile?.androidAutoProfile.orEmpty())
            .putInt("active_mood_crossfade_ms", profile?.crossfadeDurationMs?.toInt() ?: 1_500)
            .putString("active_mood_eq_profile", profile?.equalizerProfile.orEmpty())
            .apply()
        Log.i(TAG, "MOOD_SELECTED source=android_auto_browse mood=$mood reason=$reason profile=${profile?.androidAutoProfile}")
        main.post { updateLayout() }
    }

    private fun carHomeItems(): List<MediaItem> {
        val prefs = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
        val layoutId = prefs.getString("auto_layout_mode", AUTO_LAYOUT_MODES.first())
        val savedOrder = prefs.getString(AndroidAutoHomeLayouts.ORDER_PREFERENCE, null)
        val suggestion = carMoodSuggestion()
        val activeMood = prefs.getString("discovery_mood", null)
            ?.takeIf { SmartMoodEngine.profile(it) != null } ?: suggestion.mood
        val lastMood = prefs.getString("auto_mood_last", null)?.takeIf(String::isNotBlank)
        val layoutItems = AndroidAutoHomeLayouts.orderedSections(layoutId, savedOrder).map { section ->
            when (section) {
                "continue" -> continueListeningTile()
                "mood_hub" -> carTile(TAB_MOODS, "Set My Mood", "Choose a mood for your drive", AndroidAutoR.drawable.ic_auto_mood)
                "downloads" -> carTile(TAB_OFFLINE, "Downloads", "Play downloaded music", AndroidAutoR.drawable.ic_auto_downloads)
                "liked" -> carTile(TAB_LIKED, "Liked Songs", "Your saved tracks", AndroidAutoR.drawable.ic_auto_liked)
                "playlists" -> carTile(TAB_PLAYLISTS, "Playlists", "Your saved playlists", AndroidAutoR.drawable.ic_auto_playlists)
                "recent_playlists" -> carTile("home:recent_playlists", "Recent Playlists", "Pick up a playlist you played", AndroidAutoR.drawable.ic_auto_recent)
                "mood_playlists" -> carTile("home:mood_playlists", "Mood Playlists", "Browse music by mood", AndroidAutoR.drawable.ic_auto_mood)
                "recent" -> carTile(TAB_RECENT, "Recent Activity", "Recently played", AndroidAutoR.drawable.ic_auto_recent)
                "radio" -> carTile(TAB_RADIO, "Radio", "Browse radio stations", AndroidAutoR.drawable.ic_auto_radio)
                "artist_radio" -> carTile("home:artist_radio", "Artist Radio", "Music from artists you follow", AndroidAutoR.drawable.ic_auto_radio)
                "search" -> carTile(TAB_SEARCH, "Search", "Quick picks or search KILO", AndroidAutoR.drawable.ic_auto_search)
                "artists" -> carTile(TAB_ARTISTS, "Artists", "Music from artists you follow", AndroidAutoR.drawable.ic_auto_library)
                "recommended_artists" -> carTile("discovery:recommended_artists", "Recommended Artists", "Artists picked for you", AndroidAutoR.drawable.ic_auto_library)
                "rising_artists" -> carTile("discovery:rising_artists", "Rising Artists", "New artists to discover", AndroidAutoR.drawable.ic_auto_library)
                "albums" -> carTile(TAB_ALBUMS, "Albums", "Albums in your listening history", AndroidAutoR.drawable.ic_auto_playlists)
                "songs" -> carTile(TAB_SONGS, "Songs", "Your recent, liked and downloaded songs", AndroidAutoR.drawable.ic_auto_library)
                "drive_mix" -> carTile("mood:auto", "Drive Mix", suggestion.reason, AndroidAutoR.drawable.ic_auto_mood)
                "auto_mix" -> carTile("mood:auto", "AutoMix", suggestion.reason, AndroidAutoR.drawable.ic_auto_mood)
                "suggested_mood" -> moodCard(
                    suggestion.mood,
                    "${moodEmoji(suggestion.mood)} Suggested: ${suggestion.mood}",
                    suggestion.reason
                )
                "mood_night_drive" -> moodCard("Night Drive")
                "mood_workout" -> moodCard("Workout")
                "mood_focus" -> moodCard("Focus")
                "mood_gaming" -> moodCard("Gaming")
                "mood_road_trip" -> moodCard("Road Trip")
                "mood_relax" -> moodCard("Relax")
                "recent_mood" -> carTile("mood:recent", "${moodEmoji(lastMood ?: suggestion.mood)} Recent Mood", lastMood ?: "Suggested ${suggestion.mood}", AndroidAutoR.drawable.ic_auto_mood)
                "mood_radio" -> carTile("home:radio_mood", "Mood Radio", "A mix shaped by $activeMood", AndroidAutoR.drawable.ic_auto_mood)
                "discovery_radio", "discovery_mix" -> carTile("home:radio_discovery", "Discovery Radio", "Find something new", AndroidAutoR.drawable.ic_auto_radio)
                "hidden_gems_radio", "hidden_gems" -> carTile("home:radio_hidden", "Hidden Gems", "Discover less familiar music", AndroidAutoR.drawable.ic_auto_radio)
                "commute_radio" -> carTile("home:radio_commute", "Commute Radio", "An easy mix for the drive", AndroidAutoR.drawable.ic_auto_radio)
                "road_trip_radio" -> carTile("home:radio_road_trip", "Road Trip Radio", "Keep the journey moving", AndroidAutoR.drawable.ic_auto_radio)
                "trending" -> carTile(TAB_TRENDING, "Trending", "Popular music right now", AndroidAutoR.drawable.ic_auto_recent)
                "new_releases" -> carTile("discovery:new_releases", "New Releases", "Recently released music", AndroidAutoR.drawable.ic_auto_recent)
                "party_mode" -> moodCard("Party", "🎉 Party Mode", "An upbeat party mix")
                "queue_control" -> continueListeningTile()
                "recently_added" -> carTile(TAB_RECENT, "Recently Added", "Your latest listening", AndroidAutoR.drawable.ic_auto_recent)
                else -> folderItem(TAB_MOODS, "Set My Mood", "Choose a mood for your drive")
            }
        }
        return (listOf(rideMixTile(prefs)) + layoutItems + carTile("journeys:home", "Music Journeys", "Night Drive · Road Trip · Commute · Discovery", AndroidAutoR.drawable.ic_auto_mood))
            .distinctBy { it.mediaId }
    }

    private fun quickPicksItems(): List<MediaItem> {
        val discoveryCategories = HomeCache.discoveryShelves
            .filter { it.songs.isNotEmpty() }
            .distinctBy { it.title.lowercase(Locale.ROOT) }
            .map { shelf ->
                val count = shelf.songs.size
                val subtitle = when (shelf.title) {
                    "Spotted, Not Started", "Discovered, not played" -> "$count songs you found but haven't played yet"
                    "First Encounters", "Fresh discoveries" -> "$count new songs shaped by your listening"
                    else -> "$count fresh picks shaped by your listening"
                }
                folderItem(
                    id = "home:picks:${Uri.encode(shelf.title)}",
                    title = shelf.title,
                    subtitle = subtitle,
                    art = autoArtworkUri(AndroidAutoR.drawable.ic_auto_radio).toString()
                )
            }
        val shelves = carHomeShelves()
        val quickSongs = quickPicksSongs(shelves)
        return discoveryCategories + songItems(quickSongs)
    }

    private fun quickPicksSongs(shelves: List<HomeShelf> = carHomeShelves()): List<SongItemClean> =
        shelves.firstOrNull { it.title.contains("quick pick", ignoreCase = true) }?.songs
            ?: shelves.firstOrNull()?.songs.orEmpty()

    private fun localLibrarySongs(): List<SongItemClean> = runCatching {
        (Favorites.songs() + SessionStore.recentSongs() + downloadedMediaItems().mapIndexed { index, item -> item.toSong(index) } + cachedHomeSongs())
            .filter { it.videoId.isNotBlank() || it.id.isNotBlank() }
            .distinctBy { it.videoId.ifBlank { it.id } }
    }.getOrDefault(emptyList())

    private fun cachedHomeSongs(): List<SongItemClean> = HomeCache.shelves
        .asSequence()
        .flatMap { it.songs.asSequence() }
        .filter { it.videoId.isNotBlank() || it.id.isNotBlank() }
        .distinctBy { it.videoId.ifBlank { it.id } }
        .toList()

    private fun childrenOf(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> carRootItems()
        parentId == TAB_HOME -> personalizedCarHomeItems()
        parentId.startsWith("tripmix:") -> {
            val (mood, durationMinutes, destination) = tripMixParameters(parentId)
            songItems(buildRideMix(mood, durationMinutes, destination))
        }
        parentId == "journeys:home" -> listOf(
            moodCard("Night Drive", "🌃 Night Drive Journey", "A familiar night mix, shaped by your artists"),
            moodCard("Road Trip", "🛣️ Road Trip Journey", "A longer mix shaped by your listening"),
            moodCard("Commute", "🚗 Commute Journey", "A quick mix for the drive"),
            moodCard("Discover", "✨ Discovery Journey", "Fresh picks from KILO")
        )
        parentId == "home:radio_mood" -> childrenOf("moodradio:${Uri.encode(activeCarMood())}")
        parentId == "home:radio_discovery" -> childrenOf("moodradio:Discovery")
        parentId == "home:radio_hidden" -> childrenOf("moodradio:Hidden Gems")
        parentId == "home:radio_commute" -> childrenOf("moodradio:Commute")
        parentId == "home:radio_road_trip" -> childrenOf("moodradio:Road Trip")
        parentId == "home:recent_playlists" -> childrenOf(TAB_PLAYLISTS)
        parentId == "home:recent_mixes" -> listOf(rideMixTile(getSharedPreferences("vybe_app_settings", MODE_PRIVATE))) + childrenOf(TAB_PLAYLISTS)
        parentId == "home:made_for_you" -> childrenOf(TAB_SHELVES)
        parentId == "home:mood_playlists" -> childrenOf(TAB_MOODS)
        parentId == "home:artist_radio" -> childrenOf(TAB_ARTISTS)
        parentId == TAB_SEARCH -> {
            val recent = SearchAnalytics.recentSearches(limit = 6)
            recent.map { query -> carTile("search:query:${Uri.encode(query)}", query, "Recent search", AndroidAutoR.drawable.ic_auto_recent) } +
                listOf("New music", "Night Drive", "Tamil Hits", "Hip-Hop", "Throwback Hits", "Workout mix")
                    .filterNot { it in recent }
                    .map { query -> carTile("search:query:${Uri.encode(query)}", query, "Popular search", AndroidAutoR.drawable.ic_auto_search) }
        }
        parentId == TAB_RADIO -> listOf(
            carTile(TAB_RADIO_BROWSE, "Browse", "Find live stations", AndroidAutoR.drawable.ic_auto_radio),
            carTile(TAB_RADIO_FAVORITES, "Favorites", "Stations you saved", AndroidAutoR.drawable.ic_auto_liked),
            carTile(TAB_RADIO_RECENT, "Recent", "Stations you played recently", AndroidAutoR.drawable.ic_auto_recent),
            carTile(TAB_RADIO_CUSTOM, "My Stations", "Your added stations", AndroidAutoR.drawable.ic_auto_library)
        )
        parentId == "radio:genres" -> {
            val profile = ProfileEngine.load(getSharedPreferences("vybe_app_settings", MODE_PRIVATE))
            (profile.favoriteGenres.sorted() + listOf("Hip-Hop", "Pop", "Rock", "EDM", "R&B", "Phonk", "Jazz", "Classical", "Indie"))
                .distinct().map { genre -> carTile("genre:${Uri.encode(genre)}", "$genre Radio", "Start a $genre station", AndroidAutoR.drawable.ic_auto_radio) }
        }
        parentId == "radio:moods" -> listOf(
            "Night Drive", "Workout", "Focus", "Chill", "Road Trip", "Rain Drive", "Romantic", "Party", "Devotional"
        ).map { mood -> carTile("moodradio:${Uri.encode(mood)}", "$mood Radio", "Start a $mood station", AndroidAutoR.drawable.ic_auto_mood) }
        parentId == "radio:regional" -> listOf("Tamil", "Telugu", "Hindi", "Kannada", "Malayalam", "Punjabi")
            .map { language -> carTile("genre:${Uri.encode(language)}", "$language Hits Radio", "Regional music in $language", AndroidAutoR.drawable.ic_auto_radio) }
        parentId == "radio:song" -> {
            val current = currentTrackSnapshot?.let { runCatching { it.toSong() }.getOrNull() }
                ?: SessionStore.recentSongs().firstOrNull()
            val query = current?.let { "songs similar to ${it.title} by ${it.artist}" } ?: "popular music discovery"
            songItems(YtRepo.searchSongs(query))
        }
        parentId == "radio:trending" -> songItems(YtRepo.searchSongs("trending popular songs ${Locale.getDefault().displayCountry}"))
        parentId == "radio:artists" -> childrenOf(TAB_ARTISTS)
        parentId == TAB_RADIO_BROWSE -> {
            val stations = (RadioStationStore.load(this, "radio_favorites") +
                RadioStationStore.load(this, "radio_recent_stations") +
                RadioStationStore.load(this, "radio_custom_stations")).distinctBy { it.id }
            radioMediaItems(stations).ifEmpty { radioBrowseQuickLinks() }
        }
        parentId.startsWith("home:picks:") -> {
            val title = Uri.decode(parentId.removePrefix("home:picks:"))
            val categorySongs = HomeCache.discoveryShelves
                .firstOrNull { it.title.equals(title, ignoreCase = true) }
                ?.songs
                .orEmpty()
            val categoryResults = songItems(categorySongs)
            val quickPicks = quickPicksSongs()
            categoryResults + if (quickPicks.isNotEmpty()) {
                listOf(folderItem(
                    id = "quickpicks:after:${Uri.encode(title)}",
                    title = "Quick Picks",
                    subtitle = "More music picked for you",
                    art = quickPicks.firstOrNull()?.coverUrl
                        ?: autoArtworkUri(AndroidAutoR.drawable.ic_auto_continue).toString()
                ))
            } else {
                emptyList()
            }
        }
        parentId.startsWith("quickpicks:after:") -> songItems(quickPicksSongs())
        parentId == TAB_GENRES -> {
            val profile = ProfileEngine.load(getSharedPreferences("vybe_app_settings", MODE_PRIVATE))
            val genres = (profile.favoriteGenres.sorted() + PERSONALIZATION_GENRES.filterNot { it in profile.favoriteGenres }).distinct()
            val regional = listOf("Tamil", "Telugu", "Hindi", "Kannada", "Malayalam", "Punjabi")
            val popular = listOf("Hip-Hop", "Pop", "Rock", "EDM", "R&B", "Phonk", "Jazz", "Classical", "Indie")
            val marked = genres.map { genre ->
                val group = when {
                    genre in regional -> "Regional"
                    genre in popular -> "Popular"
                    else -> "Niche"
                }
                carTile("genre:${Uri.encode(genre)}", "$group · $genre", "Explore $genre", AndroidAutoR.drawable.ic_auto_library)
            }
            marked
        }
        parentId == TAB_MOODS -> {
            val suggestion = carMoodSuggestion()
            val lastMood = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
                .getString("auto_mood_last", null)?.takeIf(String::isNotBlank)
            buildList {
                add(setMyMoodCard(suggestion))
                add(moodCard(suggestion.mood, "✨ Suggested: ${suggestion.mood}", suggestion.reason))
                if (lastMood != null && lastMood != suggestion.mood) {
                    add(moodCard(lastMood, "${moodEmoji(lastMood)} Recent Mood: $lastMood", "Your last selected mood"))
                }
                listOf(
                    "Night Drive", "Commute", "Road Trip", "Workout", "Focus", "Relax", "Gaming", "Party",
                    "Rain Drive", "Romantic", "Sleep", "Happy", "Sad", "Coffee Vibes"
                ).forEach { mood -> add(moodCard(mood)) }
            }
        }
        parentId == TAB_ARTISTS -> {
            androidAutoArtistNames().map { artist ->
                carTile("artist:${Uri.encode(artist)}", artist, "Songs by $artist", AndroidAutoR.drawable.ic_auto_artists)
            }.ifEmpty { listOf(folderItem(TAB_PICKS, "No artists yet", "Play or save music to see artists here")) }
        }
        parentId.startsWith("moodradio:") -> {
            val mood = Uri.decode(parentId.removePrefix("moodradio:"))
            val prefs = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
            val profile = ProfileEngine.load(prefs)
            if (SmartMoodEngine.profile(mood) != null) applyCarMoodSelection(mood)
            val excluded = MoodTrackFeedbackStore.hiddenIds(prefs, mood)
            val songs = AndroidAutoMoodRecommendationAdapter.recommend(
                this, mood, profile, excluded, matchingCachedCategorySongs(parentId)
            )
            songItems(songs)
        }
        parentId.startsWith("genre:") -> {
            val genre = Uri.decode(parentId.removePrefix("genre:"))
            val remote = runCatching { YtRepo.searchSongs(genreDiscoveryQuery(genre)) }
                .onFailure { Log.w(TAG, "Android Auto genre lookup failed genre=$genre", it) }
                .getOrDefault(emptyList())
            val candidates = remote.ifEmpty { matchingCachedCategorySongs(parentId) }
            songItems(filterGenreTitleMatches(candidates, genre))
        }
        parentId == "mood:auto" -> {
            val suggestion = carMoodSuggestion()
            applyCarMoodSelection(suggestion.mood, suggestion.reason)
            val prefs = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
            val profile = ProfileEngine.load(prefs)
            val excluded = MoodTrackFeedbackStore.hiddenIds(prefs, suggestion.mood)
            val songs = AndroidAutoMoodRecommendationAdapter.recommend(
                this, suggestion.mood, profile, excluded, localLibrarySongs()
            )
            songItems(songs)
        }
        parentId.startsWith("mood:") -> {
            val requestedMood = Uri.decode(parentId.removePrefix("mood:"))
            val prefs = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
            val mood = if (requestedMood == "recent") {
                prefs.getString("auto_mood_last", null)?.takeIf(String::isNotBlank) ?: carMoodSuggestion().mood
            } else requestedMood
            applyCarMoodSelection(mood)
            val profile = ProfileEngine.load(prefs)
            val excluded = MoodTrackFeedbackStore.hiddenIds(prefs, mood)
            val candidates = AndroidAutoMoodRecommendationAdapter.recommend(
                this, mood, profile, excluded, matchingCachedCategorySongs(parentId)
            )
            songItems(candidates)
        }
        parentId.startsWith("artist:") -> songItems(androidAutoArtistSongs(Uri.decode(parentId.removePrefix("artist:"))))
        parentId == TAB_RADIO_FAVORITES -> radioMediaItems(RadioStationStore.load(this, "radio_favorites"))
        parentId == TAB_RADIO_RECENT -> radioMediaItems(RadioStationStore.load(this, "radio_recent_stations"))
        parentId == TAB_RADIO_CUSTOM -> radioMediaItems(RadioStationStore.load(this, "radio_custom_stations"))
        parentId == TAB_OFFLINE -> offlineBrowseCategories()
        parentId == "offline:storage" -> offlineBrowseCategories().filterNot { it.mediaId == "offline:storage" }
        parentId == "offline:songs" -> downloadedMediaItems().ifEmpty {
            listOf(folderItem(TAB_PICKS, "No downloads yet", "Browse Quick Picks and save tracks for offline listening"))
        }
        parentId == "offline:playlists" || parentId == "offline:mixes" -> {
            val downloads = downloadedMediaItems()
            val downloadedIds = downloads.mapTo(mutableSetOf()) { it.mediaId }
            val allPlaylists = savedPlaylists().filter { playlist ->
                playlist.songs.any { it.videoId in downloadedIds || it.id in downloadedIds }
            }
            val playlists = if (parentId == "offline:mixes") allPlaylists.filter { it.name.startsWith("Road Mix", true) } else allPlaylists
            playlists.map { playlist ->
                folderItem("offline:playlist:${Uri.encode(playlist.id)}", playlist.name, "${playlist.songs.size} tracks", playlist.songs.firstOrNull()?.coverUrl)
            }.ifEmpty { listOf(folderItem(TAB_PICKS, "No offline playlists yet", "Save a playlist's tracks for offline listening")) }
        }
        parentId == "offline:albums" -> {
            val downloadedSongs = downloadedMediaItems().mapIndexed { index, item -> item.toSong(index) }
            downloadedSongs.filter { it.album.isNotBlank() }.groupBy { it.album.trim() }.map { (album, songs) ->
                folderItem("offline:album:${Uri.encode(album)}", album, "${songs.size} downloaded tracks", songs.firstOrNull()?.coverUrl)
            }.ifEmpty { listOf(folderItem(TAB_PICKS, "No downloaded albums yet", "Save album tracks to listen offline")) }
        }
        parentId.startsWith("offline:playlist:") -> {
            val playlistId = Uri.decode(parentId.removePrefix("offline:playlist:"))
            val playlistIds = savedPlaylists().firstOrNull { it.id == playlistId }?.songs
                ?.flatMap { listOf(it.videoId, it.id) }?.filter(String::isNotBlank)?.toSet().orEmpty()
            downloadedMediaItems().filter { it.mediaId in playlistIds }
        }
        parentId.startsWith("offline:album:") -> {
            val album = Uri.decode(parentId.removePrefix("offline:album:"))
            downloadedMediaItems().filter { it.toSong().album.equals(album, true) }
        }
        parentId.startsWith("search:query:") -> {
            val query = Uri.decode(parentId.removePrefix("search:query:"))
            songItems(YtRepo.searchSongs(query))
        }
        parentId.startsWith("radio:") && parentId != "radio:genres" && parentId != "radio:moods" && parentId != "radio:regional" -> emptyList()
        parentId == TAB_SONGS -> songItems(localLibrarySongs())
        parentId == TAB_ALBUMS -> localLibrarySongs().mapNotNull { it.album.trim().takeIf(String::isNotBlank) }
            .distinct().map { album ->
                folderItem("album:${Uri.encode(album)}", album, "Songs from this album")
            }
        parentId.startsWith("album:") -> {
            val album = Uri.decode(parentId.removePrefix("album:"))
            songItems(localLibrarySongs().filter { it.album.equals(album, ignoreCase = true) })
        }
        parentId.startsWith("discovery:") -> {
            val query = when (parentId.removePrefix("discovery:")) {
                "new_releases" -> "new releases latest popular songs"
                "rising_artists" -> "rising artists new music"
                "recommended_artists" -> "recommended artists similar to my favorite artists"
                else -> "new music discovery"
            }
            songItems(YtRepo.searchSongs(query))
        }
        parentId == TAB_PICKS -> quickPicksItems()
        parentId == TAB_BROWSE -> browseCategories()
        parentId == TAB_LISTEN_AGAIN -> songItems(
            matchingHomeShelf("listen again")?.songs?.takeIf { it.isNotEmpty() } ?: SessionStore.recentSongs()
        )
        parentId == TAB_HEARD_SHORTS -> songItems(matchingHomeShelf("short")?.songs.orEmpty())
        parentId == TAB_TRENDING -> songItems(matchingHomeShelf("trend", "viral", "top hits", "popular")?.songs.orEmpty())
        parentId == TAB_LIBRARY -> listOf(
            carTile(TAB_LIKED, "Liked Songs", "Your saved tracks", AndroidAutoR.drawable.ic_auto_liked),
            carTile(TAB_PLAYLISTS, "Playlists", "Your saved playlists", AndroidAutoR.drawable.ic_auto_playlists),
            carTile(TAB_ARTISTS, "Followed Artists", "Music from artists you follow", AndroidAutoR.drawable.ic_auto_library)
        )
        parentId == TAB_SHELVES -> carHomeShelves().mapIndexed { i, shelf ->
            folderItem("shelf:$i", shelf.title, art = shelf.songs.firstOrNull()?.coverUrl)
        }
        parentId == TAB_LIKED -> register(Favorites.songs().map { it.toMediaItem() }).ifEmpty {
            listOf(folderItem(TAB_PICKS, "No liked songs yet", "Explore Quick Picks and save favorites", art = autoArtworkUri(AndroidAutoR.drawable.ic_auto_liked).toString()))
        }
        parentId == TAB_RECENT -> songItems(SessionStore.recentSongs().ifEmpty { cachedHomeSongs() })
        parentId == TAB_CONTINUE -> {
            val saved = SessionStore.load()
            val queue = saved?.items.orEmpty()
            val currentFirst = if (saved != null && queue.isNotEmpty()) {
                queue.drop(saved.index) + queue.take(saved.index)
            } else (SessionStore.recentSongs().ifEmpty { cachedHomeSongs() }).map { it.toMediaItem() }
            register(currentFirst).ifEmpty {
                listOf(folderItem(TAB_PICKS, "Find something to play", "Quick Picks will help start your listening history"))
            }
        }
        parentId == TAB_PLAYLISTS -> {
            val localFolders = savedPlaylists().map { playlist ->
                folderItem("savedpl:${playlist.id}", playlist.name, "${playlist.songs.size} songs", playlist.songs.firstOrNull()?.coverUrl)
            }
            val remoteFolders = carHomeShelves().asSequence().flatMap { it.songs.asSequence() }
                .filter { it.playlistId.isNotBlank() }
                .distinctBy { it.playlistId }
                .map { folderItem("pl:${it.playlistId}", it.title, it.artist, it.coverUrl) }
                .toList()
            (localFolders + remoteFolders).ifEmpty {
                listOf(folderItem(TAB_PICKS, "No playlists yet", "Start with music picked for you", art = autoArtworkUri(AndroidAutoR.drawable.ic_auto_playlists).toString()))
            }
        }
        parentId.startsWith("savedpl:") -> songItems(
            savedPlaylists().firstOrNull { it.id == parentId.removePrefix("savedpl:") }?.songs.orEmpty()
        )
        parentId.startsWith("shelf:") -> {
            val i = parentId.removePrefix("shelf:").toIntOrNull() ?: -1
            songItems(carHomeShelves().getOrNull(i)?.songs.orEmpty())
        }
        parentId.startsWith("pl:") -> emptyList()
        else -> emptyList()
    }

    private fun searchItems(q: String): List<MediaItem> {
        val base = if (q.isBlank()) {
            carHomeShelves().firstOrNull()?.songs.orEmpty()
        } else {
            runCatching { YtRepo.searchSongs(q) }.onFailure { Log.w(TAG, "Base search failed query=$q", it) }.getOrDefault(emptyList())
        }
        val additions = if (q.isBlank()) emptyList() else SearchEnhancer.supplementalQueries(q, base.map { it.title })
            .take(2)
            .flatMap { variant -> runCatching { YtRepo.searchSongs(variant) }.getOrDefault(emptyList()) }
        val songs = SearchEnhancer.merge(base, additions) { it.videoId.ifBlank { it.id } }
        val hideExplicit = getSharedPreferences("vybe_app_settings", MODE_PRIVATE).getBoolean("filter_mature_content", true)
        return register(songs.filter { it.videoId.isNotEmpty() && (!hideExplicit || !it.isExplicit) }.map { it.toMediaItem() })
    }

    private fun resolveItem(item: MediaItem, includeFollowingSiblings: Boolean = true): List<MediaItem> {
        if (item.mediaId.isEmpty()) {
            val query = voiceSearchQuery(item)
            return if (query.isNotBlank()) searchItems(query)
            else SessionStore.recentSongs().map { it.toMediaItem() }.ifEmpty { searchItems("") }
        }
        index[item.mediaId]?.let { selected ->
            val group = siblings[item.mediaId].orEmpty()
            if (!includeFollowingSiblings || group.isEmpty()) return listOf(selected)
            val selectedIndex = group.indexOfFirst { it.mediaId == item.mediaId }
                .takeIf { it >= 0 } ?: group.indexOfFirst { it.mediaId == selected.mediaId }
            return if (selectedIndex <= 0) group
            else group.drop(selectedIndex) + group.take(selectedIndex)
        }
        if (item.mediaMetadata.isBrowsable == true) {
            // A browse folder is not a stream. Expand it through the same local
            // catalog/cache used by Android Auto's child callback.
            return childrenFromCache(item.mediaId).filter { it.mediaMetadata.isPlayable == true }
        }
        return listOf(item.buildUpon().setUri("vybe://stream/${item.mediaId}").build())
    }

    private fun voiceSearchQuery(item: MediaItem?): String {
        val metadata = item?.requestMetadata ?: return ""
        metadata.searchQuery?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val extras = metadata.extras ?: return ""
        return listOf(
            "android.intent.extra.MEDIA_TITLE",
            "android.intent.extra.MEDIA_ARTIST",
            "android.intent.extra.MEDIA_ALBUM",
            "android.intent.extra.MEDIA_GENRE",
            "android.intent.extra.MEDIA_PLAYLIST"
        ).mapNotNull { key -> extras.get(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
            .distinct()
            .joinToString(" ")
    }

    @UnstableApi
    private inner class AutoCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult = traceCallback("onConnect controller=${controller.packageName}") {
            if (controller.packageName == "com.google.android.projection.gearhead" &&
                getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
                    .getBoolean("auto_play_car_connect", false) &&
                !autoPlayOnCarConnectHandled
            ) {
                autoPlayOnCarConnectHandled = true
                main.post {
                    val player = session.player
                    if (!player.isPlaying) {
                        if (player.mediaItemCount == 0) {
                            val restored = SessionStore.load()
                            val resumeItems = restored?.items.orEmpty().ifEmpty {
                                SessionStore.recentSongs().map { it.toMediaItem() }
                            }
                            if (resumeItems.isNotEmpty()) {
                                val startIndex = restored?.index?.coerceIn(0, resumeItems.lastIndex) ?: 0
                                val startPosition = if (restored != null) restored.posMs else 0L
                                MediaSourceAudit.beforeMutation("set_media_items:session_restore", player)
                                player.setMediaItems(resumeItems, startIndex, startPosition)
                            }
                        }
                        if (player.mediaItemCount > 0) {
                            player.play()
                            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                                MediaSourceAudit.beforeMutation("prepare:session_restore", player)
                                player.prepare()
                            }
                        }
                    }
                }
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(LIKE_COMMAND)
                .add(QUEUE_NEXT_COMMAND)
                .add(CLOSE_APP_COMMAND)
                .add(MOOD_FOR_ME_COMMAND)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            ConnectionReliabilityManager.onConnected(controller.packageName, controller.uid)
            if (controller.packageName == "com.google.android.projection.gearhead") {
                DriveRecapStore.controllerConnected(
                    "${controller.packageName}:${controller.uid}",
                    session.player.currentMediaItem,
                    session.player.isPlaying
                )
                AndroidAutoWarmupManager.onCarConnected()
            }
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            ConnectionReliabilityManager.onDisconnected(controller.packageName, controller.uid)
            if (controller.packageName == "com.google.android.projection.gearhead") {
                DriveRecapStore.controllerDisconnected(
                    "${controller.packageName}:${controller.uid}", session.player.isPlaying
                )
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            bg("onPlaybackResumption controller=${controller.packageName}") {
                ConnectionReliabilityManager.onQueueRestoreRequested(controller.packageName)
                val player = mediaSession.player
                if (player.mediaItemCount > 0 && player.currentMediaItem != null) {
                    val queue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
                    ConnectionReliabilityManager.onQueueRestoreReady(controller.packageName, queue.size)
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.copyOf(queue),
                        player.currentMediaItemIndex.coerceIn(0, queue.lastIndex),
                        player.currentPosition.coerceAtLeast(0L)
                    )
                } else {
                    val restored = SessionStore.load()
                    val items = restored?.items?.takeIf { it.isNotEmpty() }
                        ?: SessionStore.recentSongs().map { it.toMediaItem() }
                    ConnectionReliabilityManager.onQueueRestoreReady(controller.packageName, items.size)
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.copyOf(items),
                        restored?.index?.coerceIn(0, items.lastIndex.coerceAtLeast(0)) ?: 0,
                        restored?.posMs?.coerceAtLeast(0L) ?: 0L
                    )
                }
            }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> = traceCallback("onCustomCommand controller=${controller.packageName} action=${customCommand.customAction}") {
            when (customCommand.customAction) {
                LIKE_COMMAND.customAction -> {
                    session.player.currentMediaItem?.let { Favorites.toggle(it.toSong()) }
                    updateLayout()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                QUEUE_NEXT_COMMAND.customAction -> {
                    val player = session.player
                    val current = player.currentMediaItem
                    if (current != null && player.mediaItemCount > 0) {
                        val insertAt = (player.currentMediaItemIndex.coerceAtLeast(0) + 1).coerceAtMost(player.mediaItemCount)
                        player.addMediaItem(insertAt, current)
                        SessionStore.save(player)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CLOSE_APP_COMMAND.customAction -> {
                    main.post { closeAppCompletely() }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                MOOD_FOR_ME_COMMAND.customAction -> return chooseMoodForAndroidAuto(session)
            }
            super.onCustomCommand(session, controller, customCommand, args)
        }

        private fun chooseMoodForAndroidAuto(mediaSession: MediaSession): ListenableFuture<SessionResult> {
            // Acknowledge the action immediately; catalog lookup happens off the AA callback.
            io.execute {
                try {
                    val suggestion = suggestMoodForConditions(
                        Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                        MoodContext.cachedTemperatureC(this@VybeMediaService)
                    )
                    val moodProfile = SmartMoodEngine.profile(suggestion.mood)
                    val prefs = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
                    prefs.edit()
                        .putString("discovery_mood", suggestion.mood)
                        .putString("auto_mood_last", suggestion.mood)
                        .putString("auto_mood_reason", suggestion.reason)
                        .putString("active_mood_theme", moodProfile?.themePreset.orEmpty())
                        .putString("active_mood_android_auto_profile", moodProfile?.androidAutoProfile.orEmpty())
                        .putInt("active_mood_crossfade_ms", moodProfile?.crossfadeDurationMs?.toInt() ?: 1_500)
                        .apply()
                    Log.i(TAG, "MOOD_SELECTED mood=${suggestion.mood} reason=${suggestion.reason} " +
                        "energy=${moodProfile?.energy} tempo=${moodProfile?.tempo} discovery=${moodProfile?.discovery} " +
                        "familiarity=${moodProfile?.familiarity} focus=${moodProfile?.focus} emotion=${moodProfile?.emotion} " +
                        "androidAuto=${moodProfile?.androidAutoProfile}")
                    val moodExcluded = MoodTrackFeedbackStore.hiddenIds(prefs, suggestion.mood)
                    val songs = AndroidAutoMoodRecommendationAdapter.recommend(
                        context = this@VybeMediaService,
                        mood = suggestion.mood,
                        profile = ProfileEngine.load(prefs),
                        excludedIds = moodExcluded,
                        localCandidates = localLibrarySongs()
                    )
                    val ready = validMediaItems("mood:auto", songs.map { song ->
                        val item = song.toMediaItem()
                        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
                            putString("kilo.selected_mood", suggestion.mood)
                            putString("kilo.mood_reason", suggestion.reason)
                        }
                        val metadata = item.mediaMetadata.buildUpon()
                            .setAlbumTitle("${suggestion.mood} Mix")
                            .setSubtitle("Mood: ${suggestion.mood} · ${suggestion.reason}")
                            .setExtras(extras)
                            .build()
                        item.buildUpon().setMediaMetadata(metadata).build()
                    })
                    main.post { updateLayout() }
                    if (ready.isEmpty()) {
                        Log.i(TAG, "Android Auto mood action found no playable tracks mood=${suggestion.mood}")
                    } else main.post {
                        try {
                            val player = mediaSession.player
                            PlaybackMetrics.onPlayerPrepare(ready.first().mediaId, "android_auto_mood")
                            MediaSourceAudit.beforeMutation("set_media_items:android_auto_mood", player)
                            player.setMediaItems(ready, 0, 0L)
                            player.play()
                            MediaSourceAudit.beforeMutation("prepare:android_auto_mood", player)
                            player.prepare()
                            SessionStore.save(player)
                            Log.i(TAG, "Android Auto mood action started mood=${suggestion.mood} track=${ready.first().mediaId}")
                        } catch (error: Throwable) {
                            Log.e(TAG, "Android Auto mood action handoff failed", error)
                        }
                    }
                } catch (error: Throwable) {
                    Log.e(TAG, "Android Auto mood action failed", error)
                }
            }
            Log.i(TAG, "SESSION_COMMAND command=chooseMoodForAndroidAuto resultCode=${SessionResult.RESULT_SUCCESS} status=ACCEPTED")
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> = traceCallback("onGetLibraryRoot controller=${browser.packageName}") {
            Log.i(TAG, "Browse root requested controller=${browser.packageName} signedIn=${isSignedIn()}")
            val root = folderItem(ROOT, "KILO")
            val rootResult = LibraryResult.ofItem(
                    validMediaItems(ROOT, listOf(root)).first(),
                    LibraryParams.Builder()
                        .setOffline(params?.isOffline ?: false)
                        .setRecent(params?.isRecent ?: false)
                        .setSuggested(params?.isSuggested ?: false)
                        .setExtras(Bundle(params?.extras ?: Bundle()).apply {
                            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                            putBoolean(AUTO_STYLE_SUPPORTED, true)
                            putInt(AUTO_BROWSABLE_HINT, AUTO_STYLE_GRID)
                            putInt(AUTO_PLAYABLE_HINT, AUTO_STYLE_LIST)
                        })
                        .build()
                )
            Futures.immediateFuture(logLibraryResult("onGetLibraryRoot", rootResult))
        }
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            bg("onGetChildren controller=${browser.packageName} parent=$parentId") {
                BrowsePreloadEngine.rememberOpened(this@VybeMediaService, parentId)
                try {
                // childrenFromCache returns normalized, validated items. Do not normalize
                // the whole tree a second time inside the car callback.
                val safeItems = childrenFromCache(parentId)
                val paged = safeItems.pageSafely(page, pageSize)
                Log.i(TAG, "Browse parent=$parentId total=${safeItems.size} page=$page pageSize=$pageSize returned=${paged.size}")
                warmVisibleAndroidAutoTracks(paged, parentId)
                val childStyle = AndroidAutoUiModule.childStyle(parentId)
                logLibraryResult("onGetChildren parent=$parentId", LibraryResult.ofItemList(ImmutableList.copyOf(paged), listStyleLibraryParams(params, childStyle)))
                } catch (error: Throwable) {
                    Log.e(TAG, "CHILDREN_FAILURE parent=$parentId; serving local fallback", error)
                    val fallback = runCatching {
                        withoutBrowseCycles(parentId, fallbackChildren(parentId))
                            .ifEmpty { listOf(emptyBrowseFallback(parentId)) }
                    }.onFailure { Log.e(TAG, "Fallback tree failed parent=$parentId", it) }
                        .getOrDefault(emptyList())
                    logLibraryResult("onGetChildren fallback parent=$parentId", LibraryResult.ofItemList(
                        ImmutableList.copyOf(fallback.pageSafely(page, pageSize)),
                        listStyleLibraryParams(params, AndroidAutoUiModule.childStyle(parentId))
                    ))
                }
            }
        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> = bg("onGetItem controller=${browser.packageName} mediaId=$mediaId") {
            val cached = index[mediaId] ?: runCatching { AutoBrowseCache.find(this@VybeMediaService, mediaId) }
                .onFailure { Log.e(TAG, "Room lookup failed for mediaId=$mediaId", it) }
                .getOrNull()
            val item = cached ?: if (mediaId.startsWith("radio:")) {
                val stationId = mediaId.removePrefix("radio:")
                val localStations = (RadioStationStore.load(this@VybeMediaService, "radio_favorites") +
                    RadioStationStore.load(this@VybeMediaService, "radio_recent_stations") +
                    RadioStationStore.load(this@VybeMediaService, "radio_custom_stations"))
                    .distinctBy { it.id }
                radioMediaItems(localStations).firstOrNull { it.mediaId == mediaId || it.mediaId == "radio:$stationId" }
            } else AndroidAutoPlaybackBridge.fallback(mediaId, currentTrackSnapshot)
            val valid = item?.let { validMediaItems(mediaId, listOf(it)).firstOrNull() }
            if (valid != null) {
                Log.i(TAG, "AA_GET_ITEM controller=${browser.packageName} id=$mediaId uri=${safeUriForLog(valid.localConfiguration?.uri)} " +
                    "title=${valid.mediaMetadata.title?.toString().orEmpty()} playable=${valid.mediaMetadata.isPlayable == true} " +
                    "cached=${cached != null} recovered=${cached == null} result=READY")
                PlaybackMetrics.onAndroidAutoCallback("onGetItem", mediaId, safeUriForLog(valid.localConfiguration?.uri), valid.mediaMetadata.durationMs ?: -1L, "READY")
                logLibraryResult("onGetItem mediaId=$mediaId", LibraryResult.ofItem(register(listOf(valid)).first(), null))
            } else {
                PlaybackMetrics.onAndroidAutoCallback("onGetItem", mediaId, "", -1L, "INVALID_ITEM")
                Log.e(TAG, "LIBRARY_FAILURE callback=onGetItem mediaId=$mediaId resultCode=${LibraryResult.RESULT_ERROR_BAD_VALUE} " +
                    "resultName=ERROR_BAD_VALUE cached=${cached != null} itemRecovered=${item != null}")
                logLibraryResult("onGetItem mediaId=$mediaId", LibraryResult.ofError<MediaItem>(androidx.media3.session.SessionError.ERROR_BAD_VALUE))
            }
        }
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> =
            bg("onSearch controller=${browser.packageName} query=$query") {
                val results = runCatching { validMediaItems("search:$query", searchItems(query)) }
                    .onFailure { Log.e(TAG, "SEARCH_FAILURE query=$query", it) }
                    .getOrDefault(emptyList())
                    .ifEmpty { emptyList() }
                searchCache[query] = results
                cacheChildren("search:$query", results)
                main.post { session?.notifySearchResultChanged(browser, query, results.size, params) }
                logLibraryResult("onSearch query=$query", LibraryResult.ofVoid())
            }
        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            bg("onGetSearchResult controller=${browser.packageName} query=$query") {
                val all = validMediaItems("search:$query", searchCache[query] ?: runCatching {
                    AutoBrowseCache.read(this@VybeMediaService, "search:$query")
                }.onFailure { Log.e(TAG, "Room search lookup failed query=$query", it) }.getOrDefault(emptyList()))
                    .ifEmpty { emptyList() }
                register(all)
                val paged = all.pageSafely(page, pageSize)
                warmVisibleAndroidAutoTracks(paged, "search:$query")
                logLibraryResult("onGetSearchResult query=$query", LibraryResult.ofItemList(ImmutableList.copyOf(paged), listStyleLibraryParams(params)))
            }
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            bg("onAddMediaItems controller=${controller.packageName}") {
                val includeSiblings = mediaItems.size == 1
                mediaItems.flatMap { resolveItem(it, includeFollowingSiblings = includeSiblings) }
                    .mapNotNull { AndroidAutoPlaybackBridge.validate(it, "onAddMediaItems") }
                    .toMutableList()
            }
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            bg("onSetMediaItems controller=${controller.packageName}") {
                Log.i(TAG, "AA_SELECTION_REQUEST controller=${controller.packageName} count=${mediaItems.size} " +
                    "ids=${mediaItems.joinToString { it.mediaId }} uris=${mediaItems.joinToString { safeUriForLog(it.localConfiguration?.uri) }} " +
                    "startIndex=$startIndex startPositionMs=$startPositionMs")
                val selectionStartedAt = SystemClock.elapsedRealtime()
                val selectionWallTime = System.currentTimeMillis()
                val firstRequestedItem = mediaItems.firstOrNull()
                // A selected search result can carry both its song mediaId and
                // the query that produced it. The mediaId identifies the user's
                // selection; only treat this as a voice search when no item ID
                // was supplied by the controller.
                val query = firstRequestedItem
                    ?.takeIf { it.mediaId.isBlank() }
                    ?.let(::voiceSearchQuery)
                    .orEmpty()
                val resolved = when {
                    query.isNotBlank() -> searchItems(query)
                    mediaItems.size == 1 -> resolveItem(mediaItems.single())
                    mediaItems.size > 1 -> mediaItems.flatMap {
                        resolveItem(it, includeFollowingSiblings = false)
                    }
                    else -> SessionStore.recentSongs().map { it.toMediaItem() }.ifEmpty { searchItems("") }
                }
                val validated = resolved.mapNotNull { AndroidAutoPlaybackBridge.validate(it, "onSetMediaItems") }
                PlaybackMetrics.onAndroidAutoCallback(
                    "onSetMediaItems",
                    firstRequestedItem?.mediaId.orEmpty(),
                    safeUriForLog(firstRequestedItem?.localConfiguration?.uri),
                    firstRequestedItem?.mediaMetadata?.durationMs ?: -1L,
                    "validated=${validated.size}/${resolved.size}"
                )
                if (validated.isEmpty()) throw UnsupportedOperationException("No valid playable media items")
                val idx = if (query.isNotBlank() || mediaItems.size == 1) {
                    0
                } else {
                    startIndex.coerceIn(0, validated.lastIndex)
                }
                val pos = if (query.isNotBlank()) C.TIME_UNSET else startPositionMs
                val selectedTrackId = validated[idx].mediaId
                val callbackDuration = SystemClock.elapsedRealtime() - selectionStartedAt
                PlaybackStartupMetrics.selected(
                    selectedTrackId,
                    if (controller.packageName == "com.google.android.projection.gearhead") "android_auto" else "media_controller",
                    selectionStartedAt,
                    selectionWallTime
                )
                PlaybackStartupMetrics.mediaItemsDelivered(selectedTrackId, "android_auto", callbackDuration)
                Log.i(TAG, "AA_SELECTION_HANDOFF status=READY controller=${controller.packageName} " +
                    "requested=${mediaItems.size} resolved=${resolved.size} validated=${validated.size} index=$idx positionMs=$pos " +
                    "selectedId=${validated[idx].mediaId} uri=${safeUriForLog(validated[idx].localConfiguration?.uri)}")
                MediaSession.MediaItemsWithStartPosition(validated, idx, pos)
            }
    }
}
