@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.kilo

import android.app.NotificationChannel
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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import kotlin.system.exitProcess

private const val TAG = "VybeAuto"
private const val ROOT = "root"
private const val TAB_HOME = "tab_home"
private const val TAB_BROWSE = "tab_browse"
private const val TAB_LIBRARY = "tab_library"
private const val TAB_PICKS = "tab_picks"
private const val TAB_SHELVES = "tab_shelves"
private const val TAB_LIKED = "tab_liked"
private const val TAB_RECENT = "tab_recent"
private const val TAB_CONTINUE = "tab_continue"
private const val TAB_PLAYLISTS = "tab_playlists"
private const val TAB_LISTEN_AGAIN = "tab_listen_again"
private const val TAB_HEARD_SHORTS = "tab_heard_shorts"
private const val TAB_TRENDING = "tab_trending"

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
    val t = md.title?.toString() ?: "Unknown"
    val a = md.artist?.toString() ?: "Unknown"
    return SongItemClean(
        id = mediaId,
        videoId = mediaId,
        title = t,
        artist = a,
        album = md.albumTitle?.toString() ?: "YouTube Music",
        coverUrl = md.artworkUri?.toString().orEmpty(),
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
    val m = MediaMetadata.Builder()
        .setTitle(title)
        .setIsBrowsable(true)
        .setIsPlayable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
    subtitle?.let { m.setSubtitle(it) }
    if (!art.isNullOrEmpty()) m.setArtworkUri(Uri.parse(art))
    return MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(m.build())
        .build()
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
        if (n == 0) return
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
        if (mediaItem.mediaId.isBlank()) return
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
    private var player: ExoPlayer? = null
    private const val MAX_PLAYBACK_RETRIES = 2
    private val retryAttempts = ConcurrentHashMap<String, Int>()
    private val bgPool = Executors.newCachedThreadPool()
    @Volatile private var handleCallAudioFocus = true

    fun isRetrying(videoId: String): Boolean =
        (retryAttempts[videoId] ?: 0) in 1..MAX_PLAYBACK_RETRIES

    @Synchronized
    fun get(context: Context): ExoPlayer {
        player?.let { return it }
        val app = context.applicationContext
        handleCallAudioFocus = app.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
            .getBoolean("pause_for_calls", true)
        Favorites.init(app)
        SessionStore.init(app)

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(6000)
            .setReadTimeoutMs(30000)

        val cachedFactory = CacheDataSource.Factory()
            .setCache(AudioCache.get(app))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app, http))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val resolver = ResolvingDataSource.Resolver { spec ->
            resolve(app, spec)
        }

        val p = ExoPlayer.Builder(app)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 45_000, 350, 900)
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
        // A gentle tempo lift while preserving the original pitch.
        p.playbackParameters = PlaybackParameters(1.03f, 1f)

        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.let { retryAttempts.remove(it) }
                SessionStore.save(p)
                SessionStore.record(mediaItem)
                ensureServiceRunning(app)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    p.currentMediaItem?.mediaId?.let { retryAttempts.remove(it) }
                    ensureServiceRunning(app)
                } else {
                    SessionStore.save(p)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val id = p.currentMediaItem?.mediaId ?: return
                Log.e(TAG, "Playback error ${error.errorCodeName} for $id", error)
                LosslessSource.invalidate(id)
                FastStream.invalidate(id)
                val attempt = retryAttempts.merge(id, 1) { previous, increment -> previous + increment } ?: 1
                if (attempt > MAX_PLAYBACK_RETRIES) return
                val shouldResume = p.playWhenReady
                Handler(Looper.getMainLooper()).postDelayed({
                    if (p.currentMediaItem?.mediaId == id) {
                        p.prepare()
                        if (shouldResume) p.play()
                    }
                }, 400L * attempt)
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
            val info = LosslessSource.peek(videoId)
                ?: LosslessSource.resolve(ctx, videoId)
                ?: FastStream.get(videoId) { YtRepo.fetchStream(it) }
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
            return spec.buildUpon().setUri(Uri.parse(localUriStr)).build()
        }
        val info = LosslessSource.peek(videoId)
            ?: LosslessSource.resolve(ctx, videoId)
            ?: FastStream.get(videoId) { YtRepo.fetchStream(it) }
            ?: throw IOException("No playable stream for $videoId")
        val streamHost = Uri.parse(info.url).host.orEmpty()
        val refererHeaders = if (streamHost.endsWith("youtube.com") || streamHost.endsWith("googlevideo.com")) {
            mapOf("Referer" to "https://www.youtube.com/")
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

    private val index = ConcurrentHashMap<String, MediaItem>()
    private val siblings = ConcurrentHashMap<String, List<MediaItem>>()
    private val searchCache = ConcurrentHashMap<String, List<MediaItem>>()

    private val favListener: () -> Unit = { main.post { updateLayout(); postPlaybackNotification() } }
    private val layoutListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateLayout()
            postPlaybackNotification()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            postPlaybackNotification()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            postPlaybackNotification()
        }
    }

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val p = session?.player ?: return
            when (intent?.action) {
                ACTION_TOGGLE_PLAY -> if (p.isPlaying) p.pause() else p.play()
                ACTION_NEXT_TRACK -> if (p.mediaItemCount > 0) p.seekToNextMediaItem()
                ACTION_PREV_TRACK -> if (p.mediaItemCount > 0) p.seekToPreviousMediaItem()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notifReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(notifReceiver, filter)
        }

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
            session?.player?.let {
                SessionStore.save(it)
                it.stop()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        exitProcess(0)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(notifReceiver) }
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

    private fun <T> bg(block: () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        io.execute {
            try {
                f.set(block())
            } catch (e: Throwable) {
                Log.e(TAG, "background task failed", e)
                f.setException(e)
            }
        }
        return f
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

    private fun matchingHomeShelf(vararg terms: String): HomeShelf? {
        val normalizedTerms = terms.map { it.lowercase() }
        return HomeCache.get().firstOrNull { shelf ->
            val title = shelf.title.lowercase()
            normalizedTerms.any { term -> title.contains(term) }
        }
    }

    private fun browseCategories(): List<MediaItem> {
        val shelves = HomeCache.get()
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
        parentId == ROOT -> listOf(
            folderItem(TAB_HOME, "Home", "Quick picks and continue listening"),
            folderItem(TAB_RECENT, "Recents", "Recently played"),
            folderItem(TAB_BROWSE, "Browse", "Explore YouTube Music"),
            folderItem(TAB_LIBRARY, "Library", "Your playlists and liked songs")
        )
        parentId == TAB_HOME -> {
            val prefs = getSharedPreferences("vybe_app_settings", MODE_PRIVATE)
            val mode = prefs.getString("auto_layout_mode", AUTO_LAYOUT_MODES.first()) ?: AUTO_LAYOUT_MODES.first()
            val layoutIndex = AUTO_LAYOUT_MODES.indexOf(mode).coerceAtLeast(0)
            val orderIndex = layoutIndex / 5
            val shelfLimit = layoutIndex % 5 + 2
            val continueShelf = folderItem(TAB_CONTINUE, "Continue listening", "Pick up where you left off")
            val picksShelf = folderItem(TAB_PICKS, "Quick picks", "Songs selected for you")
            val recentShelf = folderItem(TAB_RECENT, "Recently played", "Your latest listening")
            val homeShelves = HomeCache.get()
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
                "listen" to listOf(folderItem(TAB_LISTEN_AGAIN, "Listen Again", "Recently played favorites")),
                "trending" to listOf(folderItem(TAB_TRENDING, "Trending", "Popular music right now")),
                "shorts" to listOf(folderItem(TAB_HEARD_SHORTS, "Heard in Shorts", "Songs heard in YouTube Shorts")),
                "liked" to listOf(folderItem(TAB_LIKED, "Liked songs", "Your saved tracks")),
                "playlists" to listOf(folderItem(TAB_PLAYLISTS, "Playlists", "Your playlists")),
                "shelves" to shelves
            )
            layoutOrder.flatMap { sections[it].orEmpty() }
        }
        parentId == TAB_PICKS -> songItems(
            matchingHomeShelf("quick pick")?.songs ?: HomeCache.get().firstOrNull()?.songs.orEmpty()
        )
        parentId == TAB_BROWSE -> browseCategories()
        parentId == TAB_LISTEN_AGAIN -> songItems(
            matchingHomeShelf("listen again")?.songs?.takeIf { it.isNotEmpty() } ?: SessionStore.recentSongs()
        )
        parentId == TAB_HEARD_SHORTS -> songItems(
            matchingHomeShelf("short")?.songs?.takeIf { it.isNotEmpty() }
                ?: YtRepo.searchSongs("songs trending on YouTube Shorts")
        )
        parentId == TAB_TRENDING -> songItems(
            matchingHomeShelf("trend", "viral", "top hits", "popular")?.songs?.takeIf { it.isNotEmpty() }
                ?: YtRepo.searchSongs("trending music")
        )
        parentId == TAB_LIBRARY -> listOf(
            folderItem(TAB_LIKED, "Liked songs", "Your saved tracks"),
            folderItem(TAB_PLAYLISTS, "Playlists", "Your playlists")
        )
        parentId == TAB_SHELVES -> HomeCache.get().mapIndexed { i, shelf ->
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
            val remoteFolders = HomeCache.get().asSequence().flatMap { it.songs.asSequence() }
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
            songItems(HomeCache.get().getOrNull(i)?.songs.orEmpty())
        }
        parentId.startsWith("pl:") -> register(
            YtRepo.fetchPlaylistTracks(parentId.removePrefix("pl:")).map { it.toMediaItem() }
        )
        else -> emptyList()
    }

    private fun searchItems(q: String): List<MediaItem> {
        val songs = if (q.isBlank()) {
            HomeCache.get().firstOrNull()?.songs.orEmpty()
        } else {
            YtRepo.searchSongs(q)
        }
        val hideExplicit = getSharedPreferences("vybe_app_settings", MODE_PRIVATE).getBoolean("filter_mature_content", true)
        return register(songs.filter { it.videoId.isNotEmpty() && (!hideExplicit || !it.isExplicit) }.map { it.toMediaItem() })
    }

    private fun resolveItem(item: MediaItem): List<MediaItem> {
        if (item.mediaId.isEmpty()) {
            val query = voiceSearchQuery(item)
            return if (query.isNotBlank()) searchItems(query)
            else SessionStore.recentSongs().map { it.toMediaItem() }.ifEmpty { searchItems("") }
        }
        index[item.mediaId]?.let { return siblings[item.mediaId] ?: listOf(it) }
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
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
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

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
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
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    folderItem(ROOT, "KILO"),
                    LibraryParams.Builder()
                        .setOffline(params?.isOffline ?: false)
                        .setRecent(params?.isRecent ?: false)
                        .setSuggested(params?.isSuggested ?: false)
                        .setExtras(Bundle(params?.extras ?: Bundle()).apply {
                            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                        })
                        .build()
                )
            )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            bg {
                val all = runCatching { childrenOf(parentId) }.getOrDefault(emptyList())
                all.asSequence()
                    .filter { it.mediaMetadata.isPlayable == true }
                    .take(2)
                    .forEach { item -> PlayerHolder.prefetch(this@VybeMediaService, item.mediaId) }
                val paged = if (pageSize > 0 && pageSize != Int.MAX_VALUE) {
                    all.drop(page * pageSize).take(pageSize)
                } else all
                LibraryResult.ofItemList(ImmutableList.copyOf(paged), params)
            }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = index[mediaId] ?: if (mediaId == ROOT) folderItem(ROOT, "KILO") else null
            return Futures.immediateFuture(
                if (item != null) LibraryResult.ofItem(item, null)
                else LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE)
            )
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> =
            bg {
                val items = searchItems(query)
                items.take(2).forEach { item -> PlayerHolder.prefetch(this@VybeMediaService, item.mediaId) }
                searchCache[query] = items
                main.post { session.notifySearchResultChanged(browser, query, items.size, params) }
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
            bg {
                val all = searchCache[query] ?: searchItems(query)
                val paged = if (pageSize > 0 && pageSize != Int.MAX_VALUE) {
                    all.drop(page * pageSize).take(pageSize)
                } else all
                LibraryResult.ofItemList(ImmutableList.copyOf(paged), params)
            }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            bg { mediaItems.flatMap { resolveItem(it) }.toMutableList() }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            bg {
                val query = voiceSearchQuery(mediaItems.firstOrNull())
                val resolved = when {
                    query.isNotBlank() -> searchItems(query)
                    mediaItems.isNotEmpty() -> mediaItems.flatMap { resolveItem(it) }
                    else -> SessionStore.recentSongs().map { it.toMediaItem() }.ifEmpty { searchItems("") }
                }
                if (resolved.isEmpty()) throw UnsupportedOperationException("No matching music found")
                resolved.take(2).forEach { item -> PlayerHolder.prefetch(this@VybeMediaService, item.mediaId) }
                val idx = if (query.isNotBlank()) 0 else startIndex.coerceIn(0, resolved.lastIndex)
                val pos = if (query.isNotBlank()) C.TIME_UNSET else startPositionMs
                MediaSession.MediaItemsWithStartPosition(resolved, idx, pos)
            }
    }
}
