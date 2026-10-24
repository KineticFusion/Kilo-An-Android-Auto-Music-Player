@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.kilo

import android.app.NotificationChannel
import com.example.kilo.streaming.*
import com.example.kilo.profile.*
import com.example.kilo.search.SearchEnhancer
import com.example.kilo.androidauto.AndroidAutoHomeLayouts
import com.example.kilo.androidauto.AndroidAutoUiModule
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

@androidx.media3.common.util.UnstableApi
fun SongItemClean.toMediaItem(): MediaItem {
    val vid = videoId.ifEmpty { id }
    LosslessSource.register(this, vid)
    val fingerprint = listOf(title, artist, album, durationMs.toString(), coverUrl).joinToString("\u001f")
    return MediaItemCache.getOrCreate(vid, fingerprint) {
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
            shelf.title.equals("Discovered, not played", ignoreCase = true) ||
                shelf.title.equals("Fresh discoveries", ignoreCase = true) ||
                shelf.title.equals("Similar to your favorites", ignoreCase = true) ||
                shelf.title.startsWith("For your ", ignoreCase = true)
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
    private var player: ExoPlayer? = null
    private var bandwidthMeter: DefaultBandwidthMeter? = null
    private val retryAttempts = ConcurrentHashMap<String, Int>()
    private val unavailableTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val lastKnownPositionMs = ConcurrentHashMap<String, Long>()
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

    @Synchronized
    fun currentMediaItemOrNull(): MediaItem? = player?.currentMediaItem

    fun estimatedBandwidthKbps(): Long {
        val estimate = bandwidthMeter?.bitrateEstimate ?: -1L
        return if (estimate > 0L) estimate / 1_000L else -1L
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

        val p = ExoPlayer.Builder(app)
            .setBandwidthMeter(meter)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 90_000, 2_500, 5_000)
                    .build()
            )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    ResolvingDataSource.Factory(cachedFactory, resolver)
                )
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
        p.repeatMode = Player.REPEAT_MODE_ALL

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

        p.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Keep playback intent in sync with the v2.5.0 player behavior.
                lastPlayIntent = playWhenReady
                Log.i(
                    "KiloPlayback",
                    "playWhenReadyChanged id=${p.currentMediaItem?.mediaId.orEmpty()} " +
                        "value=$playWhenReady reason=$reason lastIntent=$lastPlayIntent state=${p.playbackState} " +
                        "suppression=${p.playbackSuppressionReason} positionMs=${p.currentPosition}"
                )
                PlaybackStartupMetrics.playerState(
                    p.currentMediaItem?.mediaId, p.playbackState, playWhenReady, p.isPlaying
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                logPlaybackSnapshot("stateChanged")
                PlaybackPerformanceLayer.onPlayerState(app, p, playbackState, p.isPlaying)
                PlaybackStartupMetrics.playerState(
                    p.currentMediaItem?.mediaId, playbackState, p.playWhenReady, p.isPlaying
                )
                if (playbackState == Player.STATE_READY && p.playWhenReady) {
                    lastAutoplayReadyTrackId = p.currentMediaItem?.mediaId
                }
                if (playbackState == Player.STATE_IDLE) {
                    p.currentMediaItem?.mediaId?.let { id ->
                        scheduleUnexpectedIdleRecovery(p, id, positionHandler)
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
                if (id == null) return
                Log.e("KiloPlayback", "Playback error ${error.errorCodeName} code=${error.errorCode} for $id", error)
                logPlaybackSnapshot("playerError")
                if (!id.startsWith("radio:")) {
                    LosslessSource.invalidate(id)
                    FastStream.invalidate(id)
                    QueuePreloader.forget(id)
                }
                val attempt = retryAttempts.merge(id, 1) { previous, increment -> previous + increment } ?: 1
                val retryDelay = (400L * attempt).takeIf { attempt <= MAX_PLAYBACK_RETRIES }
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
                        p.seekTo(nextIndex, 0L)
                        p.prepare()
                        if (shouldResume) p.play()
                    } else {
                        Log.e("KiloPlayback", "No other playable queue item after stream failure id=$id")
                        p.pause()
                    }
                    return
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    if (p.currentMediaItem?.mediaId == id) {
                        // prepare() after a source error can return the player to
                        // the item's default position. Retain the latest requested
                        // or confirmed position so a range failure does not restart
                        // a long track from zero.
                        val resumePositionMs = maxOf(
                            retryPositionMs,
                            lastKnownPositionMs[id] ?: 0L
                        )
                        p.seekTo(resumePositionMs)
                        p.prepare()
                        // Errors can clear playWhenReady while retaining the user's original
                        // intent. Restore it explicitly after prepare or Android Auto remains paused.
                        if (shouldResume) p.play()
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

    private fun scheduleUnexpectedIdleRecovery(p: ExoPlayer, id: String, handler: Handler) {
        val hadStarted = id == lastAudibleTrackId || id == lastAutoplayReadyTrackId ||
            p.currentPosition > 0L || (lastKnownPositionMs[id] ?: 0L) > 0L
        if (!hadStarted || !p.playWhenReady || p.playerError != null || p.mediaItemCount == 0) return
        if (!idleRecoveryScheduled.add(id)) return
        val attempt = idleRecoveryAttempts.merge(id, 1) { previous, increment -> previous + increment } ?: 1
        if (attempt > 3) {
            idleRecoveryScheduled.remove(id)
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
            "unexpectedIdleRecovery scheduled id=$id attempt=$attempt positionMs=$resumePositionMs"
        )
        handler.postDelayed({
            idleRecoveryScheduled.remove(id)
            if (p.currentMediaItem?.mediaId != id || !p.playWhenReady ||
                p.playbackState != Player.STATE_IDLE || p.playerError != null
            ) return@postDelayed
            runCatching {
                p.seekTo(resumePositionMs)
                p.prepare()
                p.play()
                Log.w("KiloPlayback", "unexpectedIdleRecovery started id=$id attempt=$attempt positionMs=$resumePositionMs")
            }.onFailure { error ->
                Log.e("KiloPlayback", "unexpectedIdleRecovery failed id=$id attempt=$attempt", error)
            }
        }, 300L * attempt)
    }

    fun prefetch(ctx: Context, videoId: String, onComplete: (() -> Unit)? = null) {
        if (videoId.isBlank()) return
        bgPool.execute {
            try {
                val info = resolveStreamInfo(ctx, videoId) ?: return@execute
                val prefetchFailure = AudioCache.prefetch(ctx, videoId, info)
                val responseCode = generateSequence<Throwable>(prefetchFailure) { it.cause }
                    .mapNotNull { (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode }
                    .firstOrNull()
                if (responseCode == 403) {
                    Log.w("KiloPlayback", "prefetch stream rejected id=$videoId http=403; refreshing before selection")
                    LosslessSource.invalidate(videoId)
                    FastStream.invalidate(videoId)
                    QueuePreloader.forget(videoId)
                    val refreshed = resolveStreamInfo(ctx, videoId)
                    if (refreshed != null) {
                        val retryFailure = AudioCache.prefetch(ctx, videoId, refreshed)
                        if (retryFailure == null) {
                            Log.i("KiloPlayback", "prefetch refresh ready id=$videoId")
                        } else {
                            Log.w("KiloPlayback", "prefetch refresh failed id=$videoId", retryFailure)
                        }
                    } else {
                        Log.w("KiloPlayback", "prefetch refresh found no stream id=$videoId")
                    }
                } else if (prefetchFailure != null) {
                    Log.w("KiloPlayback", "prefetch failed id=$videoId", prefetchFailure)
                }
            } catch (error: Throwable) {
                Log.w("KiloPlayback", "prefetch failed id=$videoId", error)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    fun resolveForWarmup(ctx: Context, videoId: String): StreamInfo? =
        resolveStreamInfo(ctx.applicationContext, videoId)

    private fun resolveStreamInfo(ctx: Context, videoId: String): StreamInfo? {
        val cacheHit = ResolvedTrackCache.peek(videoId) != null
        PlaybackMetrics.onResolverStarted(videoId, cacheHit)
        val startedAt = SystemClock.elapsedRealtime()
        val resolver = StreamResolver(listOf(
            StreamSource { id -> LosslessSource.peek(id) ?: LosslessSource.resolve(ctx, id) },
            StreamSource { id -> FastStream.get(id, { YtRepo.fetchStream(it) }, ctx) }
        ))
        val info = try {
            resolver.resolve(videoId)
        } catch (error: Throwable) {
            PlaybackMetrics.onResolverFinished(videoId, startedAt, null, error.message)
            PlaybackWarmupManager.onResolverReady()
            throw error
        }
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
            currentTrackSnapshot = mediaItem
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
            postPlaybackNotification()
            if (isPlaying && wifiLock?.isHeld == false) wifiLock?.acquire()
            else if (!isPlaying && wifiLock?.isHeld == true) wifiLock?.release()
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
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, player, AutoCallback())
            .setSessionActivity(open)
            .build()
        PlaybackWarmupManager.onMediaSessionReady()
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
        runCatching { unregisterReceiver(notifReceiver) }
        wifiLock?.let { if (it.isHeld) it.release() }
        session?.player?.let {
            SessionStore.save(it)
            it.removeListener(layoutListener)
        }
        Favorites.removeListener(favListener)
        getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(carBrowsePreferenceListener)
        session?.release()
        session = null
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

    private fun radioBrowseQuickLinks(): List<MediaItem> = listOf(
        carTile("home:radio_mood", "Mood Radio", "A music mix for your selected mood", AndroidAutoR.drawable.ic_auto_mood),
        carTile("home:radio_discovery", "Discovery Radio", "Find something new", AndroidAutoR.drawable.ic_auto_radio),
        carTile("home:radio_commute", "Commute Radio", "An easy mix for the drive", AndroidAutoR.drawable.ic_auto_continue),
        carTile("home:radio_road_trip", "Road Trip Radio", "Keep the journey moving", AndroidAutoR.drawable.ic_auto_recent)
    )

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
        val result = when (parentId) {
            ROOT -> carRootItems()
            TAB_HOME -> carHomeItems()
            "journeys:home" -> listOf(
                moodCard("Night Drive", "🌃 Night Drive Journey", "A familiar night mix, shaped by your artists"),
                moodCard("Road Trip", "🛣️ Road Trip Journey", "A longer mix shaped by your listening"),
                moodCard("Commute", "🚗 Commute Journey", "A quick mix for the drive"),
                moodCard("Discover", "✨ Discovery Journey", "Fresh picks from KILO")
            )
            TAB_SEARCH -> listOf(
                moodCard("Night Drive", "🌃 Night Drive", "Quick mood pick"),
                moodCard("Workout", "💪 Workout", "Quick mood pick"),
                moodCard("Road Trip", "🛣️ Road Trip", "Quick mood pick"),
                moodCard("Gaming", "🎮 Gaming", "Quick mood pick"),
                moodCard("Focus", "🧠 Focus", "Quick mood pick"),
                moodCard("Relax", "😌 Relax", "Quick mood pick"),
                carTile(TAB_OFFLINE, "Downloads", "Play offline songs", AndroidAutoR.drawable.ic_auto_downloads),
                carTile(TAB_LIKED, "Liked Songs", "Your saved tracks", AndroidAutoR.drawable.ic_auto_liked),
                carTile(TAB_RECENT, "Recent", "Recently played", AndroidAutoR.drawable.ic_auto_recent)
            )
            TAB_BROWSE -> listOf(
                carTile(TAB_MOODS, "Moods", "Browse music by mood", AndroidAutoR.drawable.ic_auto_mood),
                carTile(TAB_GENRES, "Genres", "Browse music by genre", AndroidAutoR.drawable.ic_auto_library),
                setMyMoodCard(),
                carTile(TAB_ARTISTS, "Your Artists", "Music from artists you follow", AndroidAutoR.drawable.ic_auto_library),
                carTile(TAB_PICKS, "Quick Picks", "Songs selected for you", AndroidAutoR.drawable.ic_auto_continue),
                carTile(TAB_TRENDING, "Trending", "Popular music right now", AndroidAutoR.drawable.ic_auto_recent)
            )
            TAB_RADIO -> {
                val prefs = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
                val savedMood = prefs.getString("discovery_mood", "Discover") ?: "Discover"
                val activeMood = SmartMoodEngine.profile(savedMood)?.name
                    ?: suggestMoodForConditions(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), MoodContext.cachedTemperatureC(this)).mood
                listOf(carTile("moodradio:${Uri.encode(activeMood)}", "$activeMood Mood Radio", "A mood-shaped music queue for your drive", AndroidAutoR.drawable.ic_auto_mood)) + listOf(
                    carTile(TAB_RADIO_BROWSE, "Browse stations", "Live stations from Radio Browser", AndroidAutoR.drawable.ic_auto_radio),
                    carTile(TAB_RADIO_FAVORITES, "Favorites", "Your saved radio stations", AndroidAutoR.drawable.ic_auto_liked),
                    carTile(TAB_RADIO_RECENT, "Recently played", "Stations you listened to", AndroidAutoR.drawable.ic_auto_recent),
                    carTile(TAB_RADIO_CUSTOM, "My stations", "Your custom stream URLs", AndroidAutoR.drawable.ic_auto_radio)
                )
            }
            TAB_LIBRARY -> listOf(
                carTile(TAB_LIKED, "Liked songs", "Your saved tracks", AndroidAutoR.drawable.ic_auto_liked),
                carTile(TAB_PLAYLISTS, "Playlists", "Your playlists", AndroidAutoR.drawable.ic_auto_playlists),
                carTile(TAB_OFFLINE, "Downloads", "Downloaded music", AndroidAutoR.drawable.ic_auto_downloads)
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
            TAB_OFFLINE -> downloadedMediaItems().ifEmpty {
                listOf(folderItem(
                    TAB_BROWSE,
                    "No downloads yet",
                    "Browse music and save tracks for offline listening",
                    art = autoArtworkUri(AndroidAutoR.drawable.ic_auto_downloads).toString()
                ))
            }
            else -> when {
                parentId.startsWith("home:") -> childrenOf(parentId)
                parentId == "mood:auto" || parentId.startsWith("mood:") || parentId.startsWith("moodradio:") || parentId.startsWith("genre:") || parentId.startsWith("artist:") -> fallbackSongs
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

    private fun cacheChildren(parentId: String, items: List<MediaItem>): List<MediaItem> {
        val safe = validMediaItems(parentId, items)
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
            ROOT, TAB_HOME, TAB_SEARCH, TAB_RECENT, TAB_CONTINUE, TAB_PICKS, TAB_LIKED, TAB_PLAYLISTS, TAB_OFFLINE,
            TAB_RADIO_FAVORITES, TAB_RADIO_RECENT, TAB_RADIO_CUSTOM,
            TAB_GENRES, TAB_MOODS, TAB_ARTISTS, TAB_ALBUMS, TAB_SONGS
        ) || parentId.startsWith("home:") || parentId.startsWith("savedpl:") || parentId.startsWith("genre:") ||
            parentId.startsWith("mood:") || parentId.startsWith("artist:") || parentId.startsWith("radio:")

    private fun requestBrowseGeneration(parentId: String) {
        if (parentId in setOf(ROOT, TAB_HOME, TAB_SEARCH, TAB_BROWSE, TAB_RADIO, TAB_RADIO_BROWSE)) return
        if (!pendingBrowseBuilds.add(parentId)) return
        io.execute {
            try {
                val generated = runCatching { childrenOf(parentId) }
                    .onFailure { Log.e(TAG, "Could not build Android Auto section $parentId", it) }
                    .getOrDefault(emptyList())
                val safe = cacheChildren(parentId, generated)
                if (safe.isNotEmpty()) main.post {
                    session?.notifyChildrenChanged(parentId, safe.size, null)
                }
            } finally {
                pendingBrowseBuilds.remove(parentId)
            }
        }
    }

    private fun ensureMoodShortcut(parentId: String, items: List<MediaItem>): List<MediaItem> {
        if (parentId != TAB_BROWSE || items.any { it.mediaId == "mood:auto" }) return items
        return items + setMyMoodCard()
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
        if (!isVolatileBrowseParent(parentId)) {
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
        val safe = fallback.ifEmpty { listOf(emptyBrowseFallback(parentId)) }
        if (safe.isNotEmpty() && !isVolatileBrowseParent(parentId)) BrowseTreeCache.put(parentId, safe)
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
        val shelves = carHomeShelves()
        val quickIndex = shelves.indexOfFirst { it.title.contains("quick pick", ignoreCase = true) }.takeIf { it >= 0 } ?: 0
        val listenIndex = shelves.indexOfFirst { it.title.contains("listen again", ignoreCase = true) }
        val shortsIndex = shelves.indexOfFirst { it.title.contains("short", ignoreCase = true) }
        val trendingIndex = shelves.indexOfFirst {
            listOf("trend", "viral", "top hits", "popular").any { term -> it.title.contains(term, ignoreCase = true) }
        }
        val knownIndexes = setOf(quickIndex, listenIndex, shortsIndex, trendingIndex).filter { it >= 0 }.toSet()
        return buildList {
            add(carTile(TAB_GENRES, "Genres", "Browse music by genre", AndroidAutoR.drawable.ic_auto_library))
            add(carTile(TAB_MOODS, "Moods", "Browse music by mood", AndroidAutoR.drawable.ic_auto_mood))
            if (ProfileEngine.load(getSharedPreferences("vybe_app_settings", MODE_PRIVATE)).followedArtists.isNotEmpty()) {
                add(carTile(TAB_ARTISTS, "Your Artists", "Music from artists you follow", AndroidAutoR.drawable.ic_auto_library))
            }
            add(folderItem(TAB_PICKS, "Quick Picks", "Songs selected for you", shelves.getOrNull(quickIndex)?.songs?.firstOrNull()?.coverUrl))
            add(folderItem(TAB_LISTEN_AGAIN, "Listen Again", "Recently played favorites", shelves.getOrNull(listenIndex)?.songs?.firstOrNull()?.coverUrl))
            add(folderItem(TAB_HEARD_SHORTS, "Heard in Shorts", "Songs heard in YouTube Shorts", shelves.getOrNull(shortsIndex)?.songs?.firstOrNull()?.coverUrl))
            add(folderItem(TAB_TRENDING, "Trending", "Popular music right now", shelves.getOrNull(trendingIndex)?.songs?.firstOrNull()?.coverUrl))
            shelves.forEachIndexed { idx, shelf ->
                if (idx !in knownIndexes) {
                    add(folderItem("shelf:$idx", shelf.title, art = shelf.songs.firstOrNull()?.coverUrl))
                }
            }
        }
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

    private fun carMoodSuggestion(): MoodSuggestion = suggestMoodForConditions(
        Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        MoodContext.cachedTemperatureC(this)
    )

    private fun activeCarMood(): String {
        val saved = getSharedPreferences("vybe_app_settings", MODE_PRIVATE).getString("discovery_mood", null)
        return saved?.takeIf { SmartMoodEngine.profile(it) != null } ?: carMoodSuggestion().mood
    }

    private fun personalizedMoodQuery(mood: String, profile: ListenerProfile): String {
        val preferredArtist = (profile.followedArtists + Favorites.songs().map { it.artist } + PlayedSongHistory.recommendationArtists())
            .asSequence().map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase(Locale.ROOT) }.firstOrNull()
        return listOf(
            moodDiscoveryQuery(mood, profile.favoriteGenres.firstOrNull()),
            preferredArtist?.let { "similar to $it" }
        ).filterNotNull().joinToString(" ")
    }

    private fun carRootItems(): List<MediaItem> {
        // Hydrate the Android Auto catalog in the background as soon as the car opens KILO.
        carHomeShelves()
        val layoutItems = carHomeItems().distinctBy { it.mediaId }
        val presentIds = layoutItems.mapTo(HashSet()) { it.mediaId }
        val quickAccess = buildList {
            val moodIsVisible = layoutItems.any {
                it.mediaId == TAB_MOODS || it.mediaId.startsWith("mood:") || it.mediaId == "mood:auto"
            }
            if (!moodIsVisible) {
                add(carTile(TAB_MOODS, "Set My Mood", "Choose music for your drive", AndroidAutoR.drawable.ic_auto_mood))
            }
            if (TAB_SEARCH !in presentIds) {
                add(carTile(TAB_SEARCH, "Search", "Find music", AndroidAutoR.drawable.ic_auto_search))
            }
            if (TAB_RADIO !in presentIds) {
                add(carTile(TAB_RADIO, "Radio", "Live and mood stations", AndroidAutoR.drawable.ic_auto_radio))
            }
        }
        return (layoutItems + quickAccess).distinctBy { it.mediaId }
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
        val id = mediaId.lowercase(Locale.ROOT)
        val label = title.lowercase(Locale.ROOT)
        val resourceId = when {
            id == TAB_CONTINUE || label.contains("continue listening") -> AndroidAutoR.drawable.ic_auto_continue
            id == TAB_PLAYLISTS || id.startsWith("savedpl:") || id.startsWith("pl:") || label.contains("playlist") -> AndroidAutoR.drawable.ic_auto_playlists
            id == TAB_OFFLINE || label.contains("download") -> AndroidAutoR.drawable.ic_auto_downloads
            id == TAB_LIKED || label.contains("liked") || label.contains("favorite") -> AndroidAutoR.drawable.ic_auto_liked
            id == TAB_RADIO || id.startsWith("home:radio") || label.contains("radio") -> AndroidAutoR.drawable.ic_auto_radio
            id == TAB_SEARCH || label == "search" -> AndroidAutoR.drawable.ic_auto_search
            id == TAB_RECENT || id == "mood:recent" || label.contains("recent") -> AndroidAutoR.drawable.ic_auto_recent
            id == TAB_MOODS || id.startsWith("mood:") || label.contains("mood") -> AndroidAutoR.drawable.ic_auto_mood
            else -> AndroidAutoR.drawable.ic_auto_library
        }
        return autoArtworkUri(resourceId)
    }

    private fun carTile(id: String, title: String, subtitle: String, artwork: Int): MediaItem =
        folderItem(id, title, subtitle, art = autoArtworkUri(artwork).toString())

    private fun moodCard(mood: String, title: String = "${moodEmoji(mood)} $mood", subtitle: String = "Music for $mood"): MediaItem =
        folderItem(
            id = "mood:${Uri.encode(mood)}",
            title = title,
            subtitle = subtitle,
            art = autoArtworkUri(AndroidAutoR.drawable.ic_auto_mood).toString(),
            childStyle = AUTO_STYLE_GRID
        )

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
        return (layoutItems + carTile("journeys:home", "Music Journeys", "Night Drive · Road Trip · Commute · Discovery", AndroidAutoR.drawable.ic_auto_mood))
            .distinctBy { it.mediaId }
    }

    private fun quickPicksItems(): List<MediaItem> {
        val discoveryCategories = HomeCache.discoveryShelves
            .filter { it.songs.isNotEmpty() }
            .distinctBy { it.title.lowercase(Locale.ROOT) }
            .map { shelf ->
                val count = shelf.songs.size
                val subtitle = if (shelf.title.equals("Discovered, not played", ignoreCase = true)) {
                    "$count songs you haven't played yet"
                } else {
                    "$count fresh picks shaped by your listening"
                }
                folderItem(
                    id = "home:picks:${Uri.encode(shelf.title)}",
                    title = shelf.title,
                    subtitle = subtitle,
                    art = autoArtworkUri(AndroidAutoR.drawable.ic_auto_radio).toString()
                )
            }
        val shelves = carHomeShelves()
        val quickSongs = shelves.firstOrNull { it.title.contains("quick pick", ignoreCase = true) }?.songs
            ?: shelves.firstOrNull()?.songs.orEmpty()
        return discoveryCategories + songItems(quickSongs)
    }

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
        parentId == TAB_HOME -> carHomeItems()
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
        parentId == "home:mood_playlists" -> childrenOf(TAB_MOODS)
        parentId == "home:artist_radio" -> childrenOf(TAB_ARTISTS)
        parentId == TAB_SEARCH -> listOf(
            moodCard("Night Drive", "🌃 Night Drive", "Quick mood pick"),
            moodCard("Workout", "💪 Workout", "Quick mood pick"),
            moodCard("Road Trip", "🛣️ Road Trip", "Quick mood pick"),
            moodCard("Gaming", "🎮 Gaming", "Quick mood pick"),
            moodCard("Focus", "🧠 Focus", "Quick mood pick"),
            moodCard("Relax", "😌 Relax", "Quick mood pick"),
            carTile(TAB_OFFLINE, "Downloads", "Play offline songs", AndroidAutoR.drawable.ic_auto_downloads),
            carTile(TAB_LIKED, "Liked Songs", "Your saved tracks", AndroidAutoR.drawable.ic_auto_liked),
            carTile(TAB_RECENT, "Recent", "Recently played", AndroidAutoR.drawable.ic_auto_recent)
        )
        parentId == TAB_RADIO -> {
            val activeMood = activeCarMood()
            listOf(carTile("moodradio:${Uri.encode(activeMood)}", "$activeMood Mood Radio", "A mood-shaped music queue for your drive", AndroidAutoR.drawable.ic_auto_mood)) + listOf(
                carTile(TAB_RADIO_BROWSE, "Browse stations", "Live stations from Radio Browser", AndroidAutoR.drawable.ic_auto_radio),
                carTile(TAB_RADIO_FAVORITES, "Favorites", "Your saved radio stations", AndroidAutoR.drawable.ic_auto_liked),
                carTile(TAB_RADIO_RECENT, "Recently played", "Stations you listened to", AndroidAutoR.drawable.ic_auto_recent),
                carTile(TAB_RADIO_CUSTOM, "My stations", "Your custom stream URLs", AndroidAutoR.drawable.ic_auto_radio)
            )
        }
        parentId == TAB_RADIO_BROWSE -> {
            val stations = (RadioStationStore.load(this, "radio_favorites") +
                RadioStationStore.load(this, "radio_recent_stations") +
                RadioStationStore.load(this, "radio_custom_stations")).distinctBy { it.id }
            radioMediaItems(stations).ifEmpty { radioBrowseQuickLinks() }
        }
        parentId.startsWith("home:picks:") -> {
            val title = Uri.decode(parentId.removePrefix("home:picks:"))
            songItems(HomeCache.discoveryShelves.firstOrNull { it.title.equals(title, ignoreCase = true) }?.songs.orEmpty())
        }
        parentId == TAB_GENRES -> {
            val profile = ProfileEngine.load(getSharedPreferences("vybe_app_settings", MODE_PRIVATE))
            (profile.favoriteGenres.sorted() + PERSONALIZATION_GENRES.filterNot { it in profile.favoriteGenres }).map { genre ->
            carTile("genre:${Uri.encode(genre)}", genre, "Browse $genre", AndroidAutoR.drawable.ic_auto_library)
            }
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
            val followed = ProfileEngine.load(getSharedPreferences("vybe_app_settings", MODE_PRIVATE)).followedArtists
            (followed + cachedHomeSongs().map { it.artist }.filter(String::isNotBlank).distinct())
                .distinct()
                .map { artist -> carTile("artist:${Uri.encode(artist)}", artist, "Songs by $artist", AndroidAutoR.drawable.ic_auto_library) }
                .ifEmpty { listOf(folderItem(TAB_PICKS, "Find artists to follow", "Open Quick Picks to start listening")) }
        }
        parentId.startsWith("moodradio:") -> {
            val mood = Uri.decode(parentId.removePrefix("moodradio:"))
            val profile = ProfileEngine.load(getSharedPreferences("vybe_app_settings", MODE_PRIVATE))
            if (SmartMoodEngine.profile(mood) != null) applyCarMoodSelection(mood)
            val songs = YtRepo.searchSongs(personalizedMoodQuery(mood, profile))
            songItems(SmartMoodEngine.rankSongs(songs, mood, PlayedSongHistory.ids(), Favorites.songs().flatMap { listOf(it.id, it.videoId) }.toSet(),
                (profile.followedArtists + Favorites.songs().map { it.artist }).toSet()))
        }
        parentId.startsWith("genre:") -> {
            val genre = Uri.decode(parentId.removePrefix("genre:"))
            songItems(filterGenreTitleMatches(YtRepo.searchSongs(genreDiscoveryQuery(genre)), genre))
        }
        parentId == "mood:auto" -> {
            val suggestion = carMoodSuggestion()
            applyCarMoodSelection(suggestion.mood, suggestion.reason)
            val profile = ProfileEngine.load(getSharedPreferences("vybe_app_settings", MODE_PRIVATE))
            val songs = YtRepo.searchSongs(personalizedMoodQuery(suggestion.mood, profile))
            songItems(SmartMoodEngine.rankSongs(songs, suggestion.mood, PlayedSongHistory.ids(), Favorites.songs().flatMap { listOf(it.id, it.videoId) }.toSet(),
                (profile.followedArtists + Favorites.songs().map { it.artist }).toSet()))
        }
        parentId.startsWith("mood:") -> {
            val requestedMood = Uri.decode(parentId.removePrefix("mood:"))
            val prefs = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
            val mood = if (requestedMood == "recent") {
                prefs.getString("auto_mood_last", null)?.takeIf(String::isNotBlank) ?: carMoodSuggestion().mood
            } else requestedMood
            applyCarMoodSelection(mood)
            val profile = ProfileEngine.load(prefs)
            val candidates = filterMoodTitleMatches(YtRepo.searchSongs(personalizedMoodQuery(mood, profile)), mood)
            songItems(SmartMoodEngine.rankSongs(candidates, mood, PlayedSongHistory.ids(), Favorites.songs().flatMap { listOf(it.id, it.videoId) }.toSet(),
                (profile.followedArtists + Favorites.songs().map { it.artist }).toSet()))
        }
        parentId.startsWith("artist:") -> songItems(YtRepo.searchSongs("songs by ${Uri.decode(parentId.removePrefix("artist:"))}"))
        parentId == TAB_RADIO_FAVORITES -> radioMediaItems(RadioStationStore.load(this, "radio_favorites"))
        parentId == TAB_RADIO_RECENT -> radioMediaItems(RadioStationStore.load(this, "radio_recent_stations"))
        parentId == TAB_RADIO_CUSTOM -> radioMediaItems(RadioStationStore.load(this, "radio_custom_stations"))
        parentId == TAB_OFFLINE -> downloadedMediaItems().ifEmpty {
            listOf(folderItem(
                TAB_BROWSE,
                "No downloads yet",
                "Browse music and save tracks for offline listening",
                art = autoArtworkUri(AndroidAutoR.drawable.ic_auto_downloads).toString()
            ))
        }
        parentId == TAB_HOME -> carHomeItems()
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
            carTile(TAB_PLAYLISTS, "Playlists", "Your playlists", AndroidAutoR.drawable.ic_auto_playlists),
            carTile(TAB_OFFLINE, "Downloads", "Downloaded music", AndroidAutoR.drawable.ic_auto_downloads),
            carTile(TAB_RECENT, "Recent Activity", "Recently played", AndroidAutoR.drawable.ic_auto_recent)
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
                                player.setMediaItems(resumeItems, startIndex, startPosition)
                            }
                        }
                        if (player.mediaItemCount > 0) {
                            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                                player.prepare()
                            }
                            player.play()
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

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            bg("onPlaybackResumption controller=${controller.packageName}") {
                val player = mediaSession.player
                if (player.mediaItemCount > 0 && player.currentMediaItem != null) {
                    val queue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.copyOf(queue),
                        player.currentMediaItemIndex.coerceIn(0, queue.lastIndex),
                        player.currentPosition.coerceAtLeast(0L)
                    )
                } else {
                    val restored = SessionStore.load()
                    val items = restored?.items?.takeIf { it.isNotEmpty() }
                        ?: SessionStore.recentSongs().map { it.toMediaItem() }
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
                    val genre = ProfileEngine.load(prefs).favoriteGenres.firstOrNull()
                    val hideExplicit = prefs.getBoolean("filter_mature_content", true)
                    val candidates = YtRepo.searchSongs(moodDiscoveryQuery(suggestion.mood, genre))
                        .filter { it.videoId.isNotBlank() && (!hideExplicit || !it.isExplicit) }
                        .distinctBy { it.videoId }
                    val songs = SmartMoodEngine.rankSongs(
                        candidates,
                        suggestion.mood,
                        PlayedSongHistory.ids(),
                        Favorites.songs().flatMap { listOf(it.id, it.videoId) }.toSet()
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
                            player.setMediaItems(ready, 0, 0L)
                            player.prepare()
                            player.play()
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
                try {
                // childrenFromCache returns normalized, validated items. Do not normalize
                // the whole tree a second time inside the car callback.
                val safeItems = childrenFromCache(parentId)
                val paged = safeItems.pageSafely(page, pageSize)
                Log.i(TAG, "Browse parent=$parentId total=${safeItems.size} page=$page pageSize=$pageSize returned=${paged.size}")
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
