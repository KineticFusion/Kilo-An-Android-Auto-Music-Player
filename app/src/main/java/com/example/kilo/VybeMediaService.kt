@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.kilo

import android.app.NotificationChannel
import com.example.kilo.streaming.*
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

private const val TAG = "VybeAuto"
private const val ROOT = "root"
private const val AUTO_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
private const val AUTO_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
private const val AUTO_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
private const val AUTO_STYLE_LIST = 1
private const val TAB_HOME = "tab_home"
private const val TAB_BROWSE = "tab_browse"
private const val TAB_LIBRARY = "tab_library"
private const val TAB_OFFLINE = "tab_offline"
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

private val LIKE_COMMAND = SessionCommand("vybe_like", Bundle.EMPTY)
private val QUEUE_NEXT_COMMAND = SessionCommand("vybe_queue_next", Bundle.EMPTY)
private val CLOSE_APP_COMMAND = SessionCommand("vybe_close_app", Bundle.EMPTY)

const val ACTION_TOGGLE_PLAY = "com.example.kilo.ACTION_TOGGLE_PLAY"
const val ACTION_NEXT_TRACK = "com.example.kilo.ACTION_NEXT_TRACK"
const val ACTION_PREV_TRACK = "com.example.kilo.ACTION_PREV_TRACK"
const val ACTION_CLOSE_APP = "com.example.kilo.ACTION_CLOSE_APP"
private const val NOTIF_CHANNEL_ID = "vybe_playback_channel"
private const val NOTIF_ID = 4041

@androidx.media3.common.util.UnstableApi
fun SongItemClean.toMediaItem(): MediaItem {
    val vid = videoId.ifEmpty { id }
    LosslessSource.register(this, vid)
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
    return MediaItem.Builder()
        .setMediaId(vid)
        .setUri("vybe://stream/$vid")
        .setMediaMetadata(meta.build())
        .build()
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
    art: String? = null
): MediaItem {
    val artwork = art?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?.takeIf { it.scheme?.lowercase() in setOf("http", "https", "content", "android.resource") }
    val m = MediaMetadata.Builder()
        .setTitle(title)
        .setIsBrowsable(true)
        .setIsPlayable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MIXED)
        .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
        .setExtras(autoBrowseStyleExtras(isBrowsable = true))
    subtitle?.let { m.setSubtitle(it) }
    artwork?.let(m::setArtworkUri)
    return MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(m.build())
        .build()
}

private fun autoBrowseStyleExtras(isBrowsable: Boolean): Bundle = Bundle().apply {
    putBoolean(AUTO_STYLE_SUPPORTED, true)
    putInt(AUTO_BROWSABLE_HINT, AUTO_STYLE_LIST)
    putInt(AUTO_PLAYABLE_HINT, AUTO_STYLE_LIST)
}

private fun listStyleLibraryParams(params: LibraryParams?): LibraryParams =
    LibraryParams.Builder()
        .setOffline(params?.isOffline ?: false)
        .setRecent(params?.isRecent ?: false)
        .setSuggested(params?.isSuggested ?: false)
        .setExtras(Bundle(params?.extras ?: Bundle()).apply {
            putBoolean(AUTO_STYLE_SUPPORTED, true)
            putInt(AUTO_BROWSABLE_HINT, AUTO_STYLE_LIST)
            putInt(AUTO_PLAYABLE_HINT, AUTO_STYLE_LIST)
        })
        .build()

private fun <T> List<T>.pageSafely(page: Int, pageSize: Int): List<T> {
    if (page < 0 || pageSize <= 0) return this
    val from = page.toLong() * pageSize.toLong()
    if (from >= size) return emptyList()
    val to = minOf(from + pageSize.toLong(), size.toLong()).toInt()
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
    @Volatile
    private var time = 0L
    @Volatile
    private var lastAttempt = 0L

    fun set(list: List<HomeShelf>) {
        shelves = list
        time = System.currentTimeMillis()
    }

    fun get(): List<HomeShelf> {
        val now = System.currentTimeMillis()
        if (!YtRepo.userAuthCookie.contains("SAPISID")) {
            set(emptyList())
            return emptyList()
        }
        val stale = shelves.isEmpty() || now - time > 900_000L
        if (stale && now - lastAttempt > 30_000L) {
            lastAttempt = now
            runCatching { YtRepo.fetchHome() }
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
    private val bgPool = Executors.newCachedThreadPool()
    @Volatile private var handleCallAudioFocus = true

    fun isRetrying(videoId: String): Boolean =
        (retryAttempts[videoId] ?: 0) in 1..MAX_PLAYBACK_RETRIES

    fun estimatedBandwidthKbps(): Long {
        val estimate = bandwidthMeter?.bitrateEstimate ?: -1L
        return if (estimate > 0L) estimate / 1_000L else -1L
    }

    @Synchronized
    fun get(context: Context): ExoPlayer {
        player?.let { return it }
        val app = context.applicationContext
        handleCallAudioFocus = app.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
            .getBoolean("pause_for_calls", true)
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
            override fun onPlaybackStateChanged(playbackState: Int) {
                logPlaybackSnapshot("stateChanged")
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

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                Log.i("KiloPlayback", "timelineChanged reason=$reason windows=${timeline.windowCount} periods=${timeline.periodCount}")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.i(
                    "KiloPlayback",
                    "mediaItemTransition reason=$reason id=${mediaItem?.mediaId.orEmpty()} " +
                        "title=${mediaItem?.mediaMetadata?.title?.toString().orEmpty()} uri=${mediaItem?.localConfiguration?.uri?.scheme}"
                )
                // Error recovery replaces the current item in the playlist. Keep its
                // failure count across that synthetic transition so repeated 403s
                // cannot restart the same retry forever.
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    mediaItem?.mediaId?.let { retryAttempts.remove(it) }
                }
                SessionStore.save(p)
                ensureServiceRunning(app)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    val playingId = p.currentMediaItem?.mediaId
                    if (playingId != null) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (p.currentMediaItem?.mediaId == playingId && p.isPlaying && p.currentPosition >= 10_000L) {
                                retryAttempts.remove(playingId)
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
                val id = p.currentMediaItem?.mediaId ?: return
                Log.e("KiloPlayback", "Playback error ${error.errorCodeName} for $id", error)
                logPlaybackSnapshot("playerError")
                if (!id.startsWith("radio:")) {
                    LosslessSource.invalidate(id)
                    FastStream.invalidate(id)
                }
                val attempt = retryAttempts.merge(id, 1) { previous, increment -> previous + increment } ?: 1
                val retryDelay = (400L * attempt).takeIf { attempt <= MAX_PLAYBACK_RETRIES }
                val failureText = generateSequence<Throwable>(error) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString(" ")
                val streamUnavailable = failureText.contains("No playable stream", ignoreCase = true) ||
                    failureText.contains("video is unavailable", ignoreCase = true)
                val shouldResume = p.playWhenReady
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
                        if (shouldResume && p.playWhenReady) p.play()
                    }
                }, retryDelay)
            }
        })
        player = p
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

    fun prefetch(ctx: Context, videoId: String) {
        if (videoId.isBlank()) return
        bgPool.execute {
            val info = StreamResolver(listOf(
                StreamSource { id -> LosslessSource.peek(id) ?: LosslessSource.resolve(ctx, id) },
                StreamSource { id -> FastStream.get(id, { YtRepo.fetchStream(it) }, ctx) }
            )).resolve(videoId)
                ?: return@execute
            AudioCache.prefetch(ctx, videoId, info)
        }
    }

    private fun resolve(ctx: Context, spec: DataSpec): DataSpec {
        val uri = spec.uri
        if (uri.scheme != "vybe") return spec
        val videoId = uri.lastPathSegment ?: throw IOException("Bad vybe uri: $uri")
        val prefs = ctx.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
        val downloads = runCatching { JSONObject(prefs.getString("downloaded_song_uris", "{}") ?: "{}") }.getOrNull()
        val localUriStr = sequenceOf(videoId, LosslessSource.song(videoId)?.id)
            .filterNotNull().distinct().mapNotNull { downloads?.optString(it)?.takeIf { value -> value.isNotBlank() && value != "null" } }
            .firstOrNull()
        if (localUriStr != null) {
            Log.i("KiloPlayback", "streamResolved id=$videoId source=download uriScheme=${Uri.parse(localUriStr).scheme}")
            return spec.buildUpon().setUri(Uri.parse(localUriStr)).build()
        }
        val resolveStartedAt = SystemClock.elapsedRealtime()
            val info = StreamResolver(listOf(
            StreamSource { id -> LosslessSource.peek(id) ?: LosslessSource.resolve(ctx, id) },
            StreamSource { id -> FastStream.get(id, { YtRepo.fetchStream(it) }, ctx) }
        )).resolve(videoId)
            ?: throw IOException("No playable stream for $videoId")
        val streamHost = Uri.parse(info.url).host.orEmpty()
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
    private val lastCatalogRefreshAt = ConcurrentHashMap<String, Long>()

    private val favListener: () -> Unit = { main.post { updateLayout(); postPlaybackNotification() } }
    private val layoutListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
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
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, player, AutoCallback())
            .setSessionActivity(open)
            .build()
        Log.i(TAG, "MediaLibrarySession created sessionAvailable=${session != null} playerItems=${player.mediaItemCount}")
        
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KiloPlaybackWifiLock")
        
        player.addListener(layoutListener)
        Favorites.addListener(favListener)
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
            .setIconResId(android.R.drawable.btn_star_big_on)
            .setSessionCommand(LIKE_COMMAND)
            .build()
        val queueBtn = CommandButton.Builder()
            .setDisplayName("Queue next")
            .setIconResId(android.R.drawable.ic_input_add)
            .setSessionCommand(QUEUE_NEXT_COMMAND)
            .build()
        val closeBtn = CommandButton.Builder()
            .setDisplayName("Close App")
            .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
            .setSessionCommand(CLOSE_APP_COMMAND)
            .build()
        s.setCustomLayout(ImmutableList.of(likeBtn, queueBtn, closeBtn))
    }

    private fun <T> bg(callbackName: String, block: () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        io.execute {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                future.set(block())
            } catch (error: Throwable) {
                Log.e(TAG, "$callbackName failed", error)
                future.setException(error)
            } finally {
                Log.d(TAG, "$callbackName durationMs=${SystemClock.elapsedRealtime() - startedAt}")
            }
        }
        return future
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
    private fun carHomeShelves(): List<HomeShelf> {
        val cached = HomeCache.shelves
        if (cached.isEmpty()) refreshHomeCatalogAsync()
        return cached
    }

    private fun refreshHomeCatalogAsync() {
        if (!YtRepo.userAuthCookie.contains("SAPISID") || !homeRefreshInProgress.compareAndSet(false, true)) return
        if (!allowCatalogRefresh("home", 30_000L)) {
            homeRefreshInProgress.set(false)
            return
        }
        io.execute {
            try {
                val loaded = runCatching { YtRepo.fetchHome() }
                    .onFailure { Log.e(TAG, "Android Auto home catalog refresh failed", it) }
                    .getOrDefault(emptyList())
                if (loaded.isNotEmpty()) {
                    HomeCache.set(loaded)
                    val refreshedParents = (
                        listOf(TAB_HOME, TAB_BROWSE, TAB_PICKS, TAB_TRENDING, TAB_HEARD_SHORTS, TAB_LISTEN_AGAIN, TAB_SHELVES, TAB_PLAYLISTS) +
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
        val artworkUri = item.mediaMetadata.artworkUri?.takeIf {
            it.scheme?.lowercase() in setOf("http", "https", "content", "android.resource")
        }
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            putBoolean(AUTO_STYLE_SUPPORTED, true)
            putInt(AUTO_BROWSABLE_HINT, AUTO_STYLE_LIST)
            putInt(AUTO_PLAYABLE_HINT, AUTO_STYLE_LIST)
        }
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
        item.buildUpon().setMediaId(id).setMediaMetadata(metadata.build()).build()
        }
    }

    private fun fallbackChildren(parentId: String): List<MediaItem> {
        if (!isSignedIn()) {
            val onlyOffline = when (parentId) {
                ROOT -> listOf(folderItem(TAB_OFFLINE, "Offline Vault", "Downloaded music available without sign-in"))
                TAB_OFFLINE -> downloadedMediaItems()
                else -> emptyList()
            }
            return validMediaItems(parentId, onlyOffline)
        }
        val localSongs = (SessionStore.recentSongs() + Favorites.songs())
            .distinctBy { it.videoId.ifBlank { it.id } }
        val fallbackSongs = localSongs.map { it.toMediaItem() }
        val result = when (parentId) {
            ROOT, TAB_HOME, TAB_BROWSE, TAB_RADIO, TAB_LIBRARY -> childrenOf(parentId)
            TAB_PICKS, TAB_RECENT, TAB_CONTINUE, TAB_LISTEN_AGAIN, TAB_TRENDING, TAB_HEARD_SHORTS, TAB_LIKED ->
                fallbackSongs
            TAB_PLAYLISTS -> savedPlaylists().map { playlist ->
                folderItem("savedpl:${playlist.id}", playlist.name, "${playlist.songs.size} songs", playlist.songs.firstOrNull()?.coverUrl)
            }
            TAB_RADIO_BROWSE -> radioMediaItems(
                (RadioStationStore.load(this, "radio_favorites") +
                    RadioStationStore.load(this, "radio_recent_stations") +
                    RadioStationStore.load(this, "radio_custom_stations")).distinctBy { it.id }
            )
            else -> when {
                parentId.startsWith("savedpl:") -> savedPlaylists()
                    .firstOrNull { it.id == parentId.removePrefix("savedpl:") }?.songs?.map { it.toMediaItem() }.orEmpty()
                parentId.startsWith("shelf:") -> fallbackSongs
                parentId.startsWith("pl:") -> emptyList()
                else -> emptyList()
            }
        }
        return validMediaItems(parentId, result)
    }

    private fun cacheChildren(parentId: String, items: List<MediaItem>): List<MediaItem> {
        val safe = validMediaItems(parentId, items)
        if (!isVolatileBrowseParent(parentId)) runCatching {
            // Empty-state rows must stay transient so new content can replace them later.
            AutoBrowseCache.write(this, parentId, safe.filterNot { it.mediaId.startsWith("info:") })
        }
            .onFailure { Log.e(TAG, "Could not cache Android Auto section $parentId", it) }
        return safe
    }

    private fun isVolatileBrowseParent(parentId: String): Boolean =
        parentId in setOf(
            TAB_RECENT,
            TAB_CONTINUE,
            TAB_LIKED,
            TAB_PLAYLISTS,
            TAB_OFFLINE,
            TAB_RADIO_FAVORITES,
            TAB_RADIO_RECENT,
            TAB_RADIO_CUSTOM
        ) || parentId.startsWith("savedpl:")

    private fun childrenFromCache(parentId: String): List<MediaItem> {
        if (!isSignedIn()) {
            val allowed = when (parentId) {
                ROOT -> listOf(folderItem(TAB_OFFLINE, "Offline Vault", "Downloaded music available without sign-in"))
                TAB_OFFLINE -> downloadedMediaItems()
                else -> emptyList()
            }
            val safe = validMediaItems(parentId, allowed)
            return register(safe)
        }
        val cached = if (isVolatileBrowseParent(parentId)) emptyList() else runCatching {
            AutoBrowseCache.read(this, parentId)
        }.onFailure { Log.e(TAG, "Could not read cached Android Auto section $parentId", it) }
            .getOrDefault(emptyList())
            .filterNot { it.mediaId.startsWith("info:") }
        if (cached.isNotEmpty()) {
            val validCached = validMediaItems(parentId, cached)
            if (validCached.isNotEmpty()) {
                requestRemoteRefresh(parentId)
                return register(validCached)
            }
        }
        val generated = runCatching { childrenOf(parentId) }
            .onFailure { Log.e(TAG, "Could not build Android Auto section $parentId", it) }
            .getOrDefault(emptyList())
        val safe = cacheChildren(parentId, generated).ifEmpty { fallbackChildren(parentId) }
        if (safe.isNotEmpty()) cacheChildren(parentId, safe)
        requestRemoteRefresh(parentId)
        // Android Auto can report a nonrecoverable browse error for an empty
        // initial section while its remote refresh is still in flight. Give it
        // a valid route back into the library; do not persist this temporary row.
        return register(safe.ifEmpty {
            listOf(folderItem(TAB_HOME, "Home", "Browse KILO music"))
        })
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

    private fun childrenOf(parentId: String): List<MediaItem> = when {
        parentId == ROOT && !isSignedIn() -> listOf(
            folderItem(TAB_OFFLINE, "Offline Vault", "Downloaded music available without sign-in")
        )
        parentId == ROOT -> listOf(
            folderItem(TAB_HOME, "Home", "Quick picks and continue listening"),
            folderItem(TAB_RECENT, "Recents", "Recently played"),
            folderItem(TAB_BROWSE, "Browse", "Explore YouTube Music"),
            folderItem(TAB_RADIO, "Radio", "Live internet radio stations"),
            folderItem(TAB_LIBRARY, "Library", "Your playlists and liked songs"),
            folderItem(TAB_OFFLINE, "Offline Vault", "Downloaded music")
        )
        parentId == TAB_RADIO -> listOf(
            folderItem(TAB_RADIO_BROWSE, "Browse stations", "Live stations from Radio Browser"),
            folderItem(TAB_RADIO_FAVORITES, "Favorites", "Your saved radio stations"),
            folderItem(TAB_RADIO_RECENT, "Recently played", "Stations you listened to"),
            folderItem(TAB_RADIO_CUSTOM, "My stations", "Your custom stream URLs")
        )
        parentId == TAB_RADIO_BROWSE -> emptyList()
        parentId == TAB_RADIO_FAVORITES -> radioMediaItems(RadioStationStore.load(this, "radio_favorites"))
        parentId == TAB_RADIO_RECENT -> radioMediaItems(RadioStationStore.load(this, "radio_recent_stations"))
        parentId == TAB_RADIO_CUSTOM -> radioMediaItems(RadioStationStore.load(this, "radio_custom_stations"))
        parentId == TAB_OFFLINE -> downloadedMediaItems()
        parentId == TAB_HOME -> {
            val prefs = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
            val mode = prefs.getString("auto_layout_mode", AUTO_LAYOUT_MODES.first()) ?: AUTO_LAYOUT_MODES.first()
            val layoutIndex = AUTO_LAYOUT_MODES.indexOf(mode).coerceAtLeast(0)
            val orderIndex = layoutIndex / 5
            val shelfLimit = layoutIndex % 5 + 2
            val homeShelves = carHomeShelves()
            val continueShelf = folderItem(
                TAB_CONTINUE, "Continue listening", "Pick up where you left off",
                SessionStore.recentSongs().firstOrNull()?.coverUrl
            )
            val picksShelf = folderItem(
                TAB_PICKS, "Quick picks", "Songs selected for you",
                matchingHomeShelf("quick pick")?.songs?.firstOrNull()?.coverUrl
                    ?: homeShelves.firstOrNull()?.songs?.firstOrNull()?.coverUrl
            )
            val recentShelf = folderItem(
                TAB_RECENT, "Recently played", "Your latest listening",
                SessionStore.recentSongs().firstOrNull()?.coverUrl
            )
            val shelves = homeShelves.mapIndexed { i, shelf ->
                folderItem("shelf:$i", shelf.title, art = shelf.songs.firstOrNull()?.coverUrl)
            }.take(shelfLimit)
            val layoutOrder = listOf(
                listOf("continue", "picks", "recent", "trending", "shelves", "liked", "playlists"),
                listOf("picks", "shelves", "continue", "recent", "shorts", "playlists", "liked"),
                listOf("recent", "continue", "listen", "picks", "liked", "shelves", "playlists"),
                listOf("trending", "picks", "shelves", "playlists", "recent", "continue", "shorts"),
                listOf("liked", "playlists", "picks", "continue", "shelves", "recent", "trending")
            )[orderIndex.coerceIn(0, 4)]
            val sections = mapOf(
                "continue" to listOf(continueShelf),
                "picks" to listOf(picksShelf),
                "recent" to listOf(recentShelf),
                "listen" to listOf(folderItem(
                    TAB_LISTEN_AGAIN, "Listen Again", "Recently played favorites",
                    matchingHomeShelf("listen again")?.songs?.firstOrNull()?.coverUrl
                        ?: SessionStore.recentSongs().firstOrNull()?.coverUrl
                )),
                "trending" to listOf(folderItem(
                    TAB_TRENDING, "Trending", "Popular music right now",
                    matchingHomeShelf("trend", "viral", "top hits", "popular")?.songs?.firstOrNull()?.coverUrl
                )),
                "shorts" to listOf(folderItem(
                    TAB_HEARD_SHORTS, "Heard in Shorts", "Songs heard in YouTube Shorts",
                    matchingHomeShelf("short")?.songs?.firstOrNull()?.coverUrl
                )),
                "liked" to listOf(folderItem(
                    TAB_LIKED, "Liked songs", "Your saved tracks", Favorites.songs().firstOrNull()?.coverUrl
                )),
                "playlists" to listOf(folderItem(
                    TAB_PLAYLISTS, "Playlists", "Your playlists",
                    savedPlaylists().firstOrNull()?.songs?.firstOrNull()?.coverUrl
                        ?: homeShelves.asSequence().flatMap { it.songs.asSequence() }
                            .firstOrNull { it.playlistId.isNotBlank() }?.coverUrl
                )),
                "shelves" to shelves
            )
            layoutOrder.flatMap { sections[it].orEmpty() }
        }
        parentId == TAB_PICKS -> songItems(
            matchingHomeShelf("quick pick")?.songs ?: carHomeShelves().firstOrNull()?.songs.orEmpty()
        )
        parentId == TAB_BROWSE -> browseCategories()
        parentId == TAB_LISTEN_AGAIN -> songItems(
            matchingHomeShelf("listen again")?.songs?.takeIf { it.isNotEmpty() } ?: SessionStore.recentSongs()
        )
        parentId == TAB_HEARD_SHORTS -> songItems(matchingHomeShelf("short")?.songs.orEmpty())
        parentId == TAB_TRENDING -> songItems(matchingHomeShelf("trend", "viral", "top hits", "popular")?.songs.orEmpty())
        parentId == TAB_LIBRARY -> listOf(
            folderItem(TAB_LIKED, "Liked songs", "Your saved tracks"),
            folderItem(TAB_PLAYLISTS, "Playlists", "Your playlists"),
            folderItem(TAB_OFFLINE, "Offline Vault", "Downloaded music")
        )
        parentId == TAB_SHELVES -> carHomeShelves().mapIndexed { i, shelf ->
            folderItem("shelf:$i", shelf.title, art = shelf.songs.firstOrNull()?.coverUrl)
        }
        parentId == TAB_LIKED -> register(Favorites.songs().map { it.toMediaItem() })
        parentId == TAB_RECENT -> songItems(SessionStore.recentSongs())
        parentId == TAB_CONTINUE -> {
            val saved = SessionStore.load()
            val queue = saved?.items.orEmpty()
            val currentFirst = if (saved != null && queue.isNotEmpty()) {
                queue.drop(saved.index) + queue.take(saved.index)
            } else SessionStore.recentSongs().map { it.toMediaItem() }
            register(currentFirst)
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
            localFolders + remoteFolders
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
        if (!YtRepo.userAuthCookie.contains("SAPISID")) return emptyList()
        val songs = if (q.isBlank()) {
            carHomeShelves().firstOrNull()?.songs.orEmpty()
        } else {
            YtRepo.searchSongs(q)
        }
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
            }
            super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> = traceCallback("onGetLibraryRoot controller=${browser.packageName}") {
            Log.i(TAG, "Browse root requested controller=${browser.packageName} signedIn=${isSignedIn()}")
            val root = folderItem(ROOT, "KILO")
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    validMediaItems(ROOT, listOf(root)).first(),
                    LibraryParams.Builder()
                        .setOffline(params?.isOffline ?: false)
                        .setRecent(params?.isRecent ?: false)
                        .setSuggested(params?.isSuggested ?: false)
                        .setExtras(Bundle(params?.extras ?: Bundle()).apply {
                            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                            putBoolean(AUTO_STYLE_SUPPORTED, true)
                            putInt(AUTO_BROWSABLE_HINT, AUTO_STYLE_LIST)
                            putInt(AUTO_PLAYABLE_HINT, AUTO_STYLE_LIST)
                        })
                        .build()
                )
            )
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
                val all = childrenFromCache(parentId)
                val safeItems = validMediaItems(parentId, all)
                val paged = safeItems.pageSafely(page, pageSize)
                Log.i(TAG, "Browse parent=$parentId total=${safeItems.size} page=$page pageSize=$pageSize returned=${paged.size}")
                LibraryResult.ofItemList(ImmutableList.copyOf(paged), listStyleLibraryParams(params))
                } catch (error: Throwable) {
                    Log.e(TAG, "CHILDREN_FAILURE parent=$parentId; serving local fallback", error)
                    val fallback = runCatching {
                        validMediaItems(parentId, fallbackChildren(parentId))
                    }.onFailure { Log.e(TAG, "Fallback tree failed parent=$parentId", it) }
                        .getOrDefault(emptyList())
                    val safe = fallback.ifEmpty {
                        listOf(folderItem(TAB_HOME, "Home", "Browse KILO music"))
                    }
                    LibraryResult.ofItemList(
                        ImmutableList.copyOf(safe.pageSafely(page, pageSize)),
                        listStyleLibraryParams(params)
                    )
                }
            }
        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> = bg("onGetItem controller=${browser.packageName} mediaId=$mediaId") {
            val item = index[mediaId] ?: runCatching { AutoBrowseCache.find(this@VybeMediaService, mediaId) }
                .onFailure { Log.e(TAG, "Room lookup failed for mediaId=$mediaId", it) }
                .getOrNull()
                ?: if (mediaId == ROOT) folderItem(ROOT, "KILO") else null
            val valid = item?.let { validMediaItems(mediaId, listOf(it)).firstOrNull() }
            if (valid != null) LibraryResult.ofItem(register(listOf(valid)).first(), null)
            else {
                Log.w(TAG, "Unknown Android Auto mediaId=$mediaId")
                LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE)
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
                LibraryResult.ofVoid()
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
                LibraryResult.ofItemList(ImmutableList.copyOf(paged), listStyleLibraryParams(params))
            }
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            bg("onAddMediaItems controller=${controller.packageName}") {
                val includeSiblings = mediaItems.size == 1
                mediaItems.flatMap { resolveItem(it, includeFollowingSiblings = includeSiblings) }.toMutableList()
            }
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            bg("onSetMediaItems controller=${controller.packageName}") {
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
                if (resolved.isEmpty()) throw UnsupportedOperationException("No matching music found")
                val idx = if (query.isNotBlank() || mediaItems.size == 1) {
                    0
                } else {
                    startIndex.coerceIn(0, resolved.lastIndex)
                }
                val pos = if (query.isNotBlank()) C.TIME_UNSET else startPositionMs
                MediaSession.MediaItemsWithStartPosition(resolved, idx, pos)
            }
    }
}
