package com.example.kilo.spotify

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.example.kilo.spotify.SpotifyLibrary
import com.example.kilo.spotify.SpotifySavedTrack
import com.example.kilo.spotify.SpotifySavedAlbum
import com.example.kilo.spotify.SpotifyFollowedArtist
import com.example.kilo.spotify.SpotifyPlaylistSummary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Spotify WebView Bridge for Kilo
 * Loads open.spotify.com in a WebView and communicates via injected JavaScript bridge
 */

// Inner class for JavaScript interface (host -> page)
@SuppressLint("JavascriptInterface")
class SpotifyHostInterface(private val webView: WebView) {
    @JavascriptInterface
    fun postMessage(json: String) {
        // Handled by controller via callback
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun SpotifyWebViewBridge(
    modifier: Modifier = Modifier,
    onReady: (SpotifyBridgeController) -> Unit = {},
    onStateChange: (BridgeSpotifyPlaybackState?) -> Unit = {},
    onQueueChange: (BridgeSpotifyQueue?) -> Unit = {},
    onLibraryChange: (SpotifyLibrary?) -> Unit = {},
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val controller by remember { mutableStateOf<SpotifyBridgeController?>(null) }
    val webViewJob by remember { mutableStateOf<Job?>(null) }

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // databaseEnabled deprecated - removed to prevent crashes
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                        if (consoleMessage.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                            Log.w("KiloSpotifyBridge", "WebView Console: ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
                        }
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url?.startsWith("https://open.spotify.com") == true) {
                            view?.let { webView ->
                                webView.injectBridge(context)
                            }
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url.toString() ?: return false
                        // Keep navigation within Spotify domain
                        return !url.startsWith("https://open.spotify.com") && !url.startsWith("https://accounts.spotify.com")
                    }
                }

                // Add JavaScript interface for host -> page communication
                val hostInterface = SpotifyHostInterface(this)
                addJavascriptInterface(hostInterface, "MyspotiHost")

                loadUrl("https://open.spotify.com")
            }
        },
        update = { webView ->
            // Updates handled via controller
        }
    )

    // Bridge controller that manages communication
    val bridgeController = remember { SpotifyBridgeControllerImpl() }

    LaunchedEffect(Unit) {
        onReady(bridgeController)
        bridgeController.setLibraryListener(onLibraryChange)
    }

    DisposableEffect(Unit) {
        onDispose {
            bridgeController.shutdown()
        }
    }
}

/**
 * Injects the Spotify bridge JavaScript into the WebView
 */
fun WebView.injectBridge(context: Context) {
    // Load bridge.js from assets and inject
    val bridgeJs = try {
        context.assets.open("spotify/bridge.js").bufferedReader().readText()
    } catch (e: Exception) {
        Log.e("KiloSpotifyBridge", "Failed to load bridge.js", e)
        return
    }

    // Inject the bridge script
    this.evaluateJavascript("(function() {$bridgeJs})()") { result ->
        Log.d("KiloSpotifyBridge", "Bridge injection result: $result")
    }
}

/**
 * Controller for Spotify WebView Bridge
 */
interface SpotifyBridgeController {
    fun play(contextUri: String, trackUri: String?, trackUid: String?, index: Int?, positionMs: Long?)
    fun pause()
    fun resume()
    fun seek(positionMs: Long)
    fun next()
    fun previous()
    fun setShuffle(on: Boolean)
    fun setRepeat(mode: Int) // 0=off, 1=context, 2=track
    fun addToQueue(uris: List<String>)
    fun removeFromQueue(items: List<QueueItem>)
    fun clearQueue()
    fun getVolume(): Float
    fun setVolume(level: Float)
    fun getToken(): TokenResult?
    fun libraryContains(uris: List<String>): List<Boolean>
    fun libraryAdd(uris: List<String>)
    fun libraryRemove(uris: List<String>)
    fun getLibrary(): SpotifyLibrary?
    fun setLibraryListener(listener: (SpotifyLibrary?) -> Unit)
    fun getState(): BridgeSpotifyPlaybackState?
    fun getQueue(): BridgeSpotifyQueue?
    fun shutdown()
}

data class BridgeSpotifyPlaybackState(
    val track: BridgeSpotifyTrack?,
    val contextUri: String?,
    val isPaused: Boolean,
    val isBuffering: Boolean,
    val positionMs: Long,
    val timestamp: Long,
    val durationMs: Long,
    val shuffle: Boolean,
    val repeat: Int, // 0=off, 1=context, 2=track
    val speed: Float,
    val restrictions: JSONObject?,
    val playbackId: String?
)

data class BridgeSpotifyTrack(
    val kind: String, // "track", "episode", "ad"
    val uri: String,
    val uid: String,
    val name: String,
    val advertiser: String?,
    val durationMs: Long,
    val artists: List<BridgeSpotifyArtist>,
    val album: BridgeSpotifyAlbum?,
    val image: String?,
    val explicit: Boolean,
    val mediaType: String?,
    val provider: String?
)

data class BridgeSpotifyArtist(
    val uri: String,
    val name: String
)

data class BridgeSpotifyAlbum(
    val uri: String,
    val name: String
)

data class BridgeSpotifyQueue(
    val current: BridgeSpotifyTrack?,
    val queued: List<BridgeSpotifyTrack>,
    val nextUp: List<BridgeSpotifyTrack>
)

data class QueueItem(
    val uri: String,
    val uid: String
)

data class TokenResult(
    val accessToken: String,
    val expiresAtMs: Long
)

private class SpotifyBridgeControllerImpl : SpotifyBridgeController {
    private val TAG = "KiloSpotifyBridge"
    private val pendingCalls = ConcurrentHashMap<Long, (Result<Any>) -> Unit>()
    private val callIdGenerator = AtomicLong(0)
    private var webView: WebView? = null
    private var bridgeReady = false
    private var currentState: BridgeSpotifyPlaybackState? = null
    private var currentQueue: BridgeSpotifyQueue? = null
    private val job = kotlinx.coroutines.SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val stateFlow = MutableStateFlow<BridgeSpotifyPlaybackState?>(null)
    private val queueFlow = MutableStateFlow<BridgeSpotifyQueue?>(null)

    // State observers
    private var stateListener: ((BridgeSpotifyPlaybackState?) -> Unit)? = null
    private var queueListener: ((BridgeSpotifyQueue?) -> Unit)? = null
    private var errorListener: ((String) -> Unit)? = null
    private var libraryListener: ((SpotifyLibrary?) -> Unit)? = null
    private var currentLibrary: SpotifyLibrary? = null

    init {
        // Could initialize WebView reference here if needed
    }

    fun setWebView(webView: WebView) {
        this.webView = webView
    }

    fun setStateListener(listener: (BridgeSpotifyPlaybackState?) -> Unit) {
        stateListener = listener
    }

    fun setQueueListener(listener: (BridgeSpotifyQueue?) -> Unit) {
        queueListener = listener
    }

    fun setErrorListener(listener: (String) -> Unit) {
        errorListener = listener
    }

    override fun setLibraryListener(listener: (SpotifyLibrary?) -> Unit) {
        libraryListener = listener
    }

    override fun play(contextUri: String, trackUri: String?, trackUid: String?, index: Int?, positionMs: Long?) {
        call("player.play", arrayOf(
            mapOf(
                "contextUri" to contextUri,
                "trackUri" to trackUri,
                "trackUid" to trackUid,
                "index" to index,
                "positionMs" to positionMs
            ).filterValues { it != null }
        ))
    }

    override fun pause() {
        call("player.pause", arrayOf())
    }

    override fun resume() {
        call("player.resume", arrayOf())
    }

    override fun seek(positionMs: Long) {
        call("player.seek", arrayOf(positionMs))
    }

    override fun next() {
        call("player.next", arrayOf())
    }

    override fun previous() {
        call("player.previous", arrayOf())
    }

    override fun setShuffle(on: Boolean) {
        call("player.setShuffle", arrayOf(on))
    }

    override fun setRepeat(mode: Int) {
        call("player.setRepeat", arrayOf(mode))
    }

    override fun addToQueue(uris: List<String>) {
        call("queue.add", arrayOf(uris))
    }

    override fun removeFromQueue(items: List<QueueItem>) {
        call("queue.remove", arrayOf(items.map { mapOf("uri" to it.uri, "uid" to it.uid) }))
    }

    override fun clearQueue() {
        call("queue.clear", arrayOf())
    }

    override fun getVolume(): Float {
        // Synchronous call not possible, return cached or default
        return 1f
    }

    override fun setVolume(level: Float) {
        call("volume.set", arrayOf(level))
    }

    override fun getToken(): TokenResult? {
        // This would need synchronous handling - implement via callback
        return null
    }

    override fun libraryContains(uris: List<String>): List<Boolean> {
        // Synchronous not possible
        return uris.map { false }
    }

    override fun libraryAdd(uris: List<String>) {
        call("library.add", arrayOf(uris))
    }

    override fun libraryRemove(uris: List<String>) {
        call("library.remove", arrayOf(uris))
    }

    override fun getLibrary(): SpotifyLibrary? = currentLibrary

    override fun getState(): BridgeSpotifyPlaybackState? = currentState

    override fun getQueue(): BridgeSpotifyQueue? = currentQueue

    override fun shutdown() {
        job.cancel()
        pendingCalls.clear()
    }

    private fun call(method: String, args: Array<Any>) {
        val id = callIdGenerator.incrementAndGet()
        val json = JSONObject().put("id", id).put("method", method).put("args", args)

        webView?.post {
            webView?.evaluateJavascript("Myspoti.call($id, '$method', ${json.getJSONArray("args").toString()});", null)
        }
    }

    fun handleMessageFromPage(json: String) {
        scope.launch {
            try {
                val msg = JSONObject(json)
                val type = msg.optString("type")

                when (type) {
                    "ready" -> {
                        bridgeReady = true
                        Log.d(TAG, "Bridge ready: ${msg.optString("version")}")
                    }
                    "state" -> {
                        val stateJson = msg.optJSONObject("state")
                        currentState = parseState(stateJson)
                        stateListener?.invoke(currentState)
                    }
                    "queue" -> {
                        val queueJson = msg.optJSONObject("queue")
                        currentQueue = parseQueue(queueJson)
                        queueListener?.invoke(currentQueue)
                    }
                    "library" -> {
                        val libraryJson = msg.optJSONObject("library")
                        currentLibrary = parseLibrary(libraryJson)
                        libraryListener?.invoke(currentLibrary)
                    }
                    "result" -> {
                        val id = msg.optLong("id")
                        val ok = msg.optBoolean("ok")
                        val callback = pendingCalls.remove(id)
                        if (callback != null) {
                            if (ok) {
                                callback(Result.success(msg.opt("value")))
                            } else {
                                callback(Result.failure(Exception(msg.optString("error", "Unknown error"))))
                            }
                        }
                    }
                    "error" -> {
                        val error = msg.optString("error", "Unknown error")
                        Log.e(TAG, "Bridge error: $error")
                        errorListener?.invoke(error)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse bridge message", e)
            }
        }
    }

    private fun parseState(json: JSONObject?): BridgeSpotifyPlaybackState? {
        if (json == null) return null
        return BridgeSpotifyPlaybackState(
            track = parseTrack(json.optJSONObject("track")),
            contextUri = json.optString("contextUri").takeIf { it.isNotBlank() },
            isPaused = json.optBoolean("isPaused"),
            isBuffering = json.optBoolean("isBuffering"),
            positionMs = json.optLong("positionMs"),
            timestamp = json.optLong("timestamp"),
            durationMs = json.optLong("durationMs"),
            shuffle = json.optBoolean("shuffle"),
            repeat = json.optInt("repeat"),
            speed = json.optDouble("speed", 1.0).toFloat(),
            restrictions = json.optJSONObject("restrictions"),
            playbackId = json.optString("playbackId").takeIf { it.isNotBlank() }
        )
    }

    private fun parseTrack(json: JSONObject?): BridgeSpotifyTrack? {
        if (json == null) return null
        val artistsArray = json.optJSONArray("artists")
        val artists = if (artistsArray != null) {
            (0 until artistsArray.length()).mapNotNull { i ->
                artistsArray.optJSONObject(i)?.let {
                    BridgeSpotifyArtist(it.optString("uri"), it.optString("name"))
                }
            }
        } else {
            emptyList()
        }
        return BridgeSpotifyTrack(
            kind = json.optString("kind"),
            uri = json.optString("uri"),
            uid = json.optString("uid"),
            name = json.optString("name"),
            advertiser = json.optString("advertiser").takeIf { it.isNotBlank() },
            durationMs = json.optLong("durationMs"),
            artists = artists,
            album = json.optJSONObject("album")?.let {
                BridgeSpotifyAlbum(it.optString("uri"), it.optString("name"))
            },
            image = json.optString("image").takeIf { it.isNotBlank() },
            explicit = json.optBoolean("explicit"),
            mediaType = json.optString("mediaType").takeIf { it.isNotBlank() },
            provider = json.optString("provider").takeIf { it.isNotBlank() }
        )
    }

    private fun parseQueue(json: JSONObject?): BridgeSpotifyQueue? {
        if (json == null) return null
        return BridgeSpotifyQueue(
            current = parseTrack(json.optJSONObject("current")),
            queued = parseTrackArray(json.optJSONArray("queued")),
            nextUp = parseTrackArray(json.optJSONArray("nextUp"))
        )
    }

    private fun parseTrackArray(json: org.json.JSONArray?): List<BridgeSpotifyTrack> {
        if (json == null) return emptyList()
        return (0 until json.length()).mapNotNull { parseTrack(json.optJSONObject(it)) }
    }

    private fun parseLibrary(json: JSONObject?): SpotifyLibrary? {
        if (json == null) return null
        val savedTracksJson = json.optJSONArray("savedTracks")
        val savedAlbumsJson = json.optJSONArray("savedAlbums")
        val followedArtistsJson = json.optJSONArray("followedArtists")
        val playlistsJson = json.optJSONArray("playlists")

        val savedTracks = parseSavedTracks(savedTracksJson)
        val savedAlbums = parseSavedAlbums(savedAlbumsJson)
        val followedArtists = parseFollowedArtists(followedArtistsJson)
        val playlists = parsePlaylists(playlistsJson)

        return SpotifyLibrary(savedTracks, playlists, savedAlbums, followedArtists)
    }

    private fun parseSavedTracks(json: org.json.JSONArray?): List<SpotifySavedTrack> {
        if (json == null) return emptyList()
        return (0 until json.length()).mapNotNull { i ->
            val item = json.optJSONObject(i) ?: return@mapNotNull null
            val track = item.optJSONObject("track") ?: return@mapNotNull null
            val trackId = track.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = track.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artistsArr = track.optJSONArray("artists")
            val artists = if (artistsArr != null && artistsArr.length() > 0) {
                (0 until artistsArr.length()).mapNotNull { artistsArr.optJSONObject(it)?.let { BridgeSpotifyArtist(it.optString("uri"), it.optString("name")) } }
            } else emptyList()
            val albumObj = track.optJSONObject("album")
            val album = albumObj?.let { BridgeSpotifyAlbum(it.optString("uri"), it.optString("name")) }
            val image = track.optString("image").takeIf { it.isNotBlank() }
            val explicit = track.optBoolean("explicit")
            val durationMs = track.optLong("duration_ms")
            val uid = track.optString("uid")
            val kind = track.optString("kind")
            val mediaType = track.optString("mediaType").takeIf { it.isNotBlank() }
            val provider = track.optString("provider").takeIf { it.isNotBlank() }
            val addedAt = item.optString("added_at").takeIf { it.isNotBlank() } ?: ""
            val bridgeTrack = BridgeSpotifyTrack(
                kind = kind,
                uri = "spotify:track:$trackId",
                uid = uid,
                name = name,
                advertiser = null,
                durationMs = durationMs,
                artists = artists,
                album = album,
                image = image,
                explicit = explicit,
                mediaType = mediaType,
                provider = provider
            )
            SpotifySavedTrack(addedAt = addedAt, track = bridgeTrack)
        }
    }

    private fun parseSavedAlbums(json: org.json.JSONArray?): List<SpotifySavedAlbum> {
        if (json == null) return emptyList()
        return (0 until json.length()).mapNotNull { i ->
            val item = json.optJSONObject(i) ?: return@mapNotNull null
            val album = item.optJSONObject("album") ?: return@mapNotNull null
            val albumId = album.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = album.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artistsArr = album.optJSONArray("artists")
            val artists = if (artistsArr != null && artistsArr.length() > 0) {
                (0 until artistsArr.length()).mapNotNull { artistsArr.optJSONObject(it)?.let { BridgeSpotifyArtist(it.optString("uri"), it.optString("name")) } }
            } else emptyList()
            val bridgeAlbum = BridgeSpotifyAlbum(uri = "spotify:album:$albumId", name = name)
            val addedAt = item.optString("added_at").takeIf { it.isNotBlank() } ?: ""
            SpotifySavedAlbum(addedAt = addedAt, album = bridgeAlbum, artists = artists)
        }
    }

    private fun parseFollowedArtists(json: org.json.JSONArray?): List<SpotifyFollowedArtist> {
        if (json == null) return emptyList()
        return (0 until json.length()).mapNotNull { i ->
            val artist = json.optJSONObject(i) ?: return@mapNotNull null
            val artistId = artist.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = artist.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val bridgeArtist = BridgeSpotifyArtist(uri = "spotify:artist:$artistId", name = name)
            SpotifyFollowedArtist(artist = bridgeArtist)
        }
    }

    private fun parsePlaylists(json: org.json.JSONArray?): List<SpotifyPlaylistSummary> {
        if (json == null) return emptyList()
        return (0 until json.length()).mapNotNull { i ->
            val pl = json.optJSONObject(i) ?: return@mapNotNull null
            val id = pl.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = pl.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = pl.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty()
            val total = pl.optJSONObject("tracks")?.optInt("total", 0) ?: 0
            val ownerObj = pl.optJSONObject("owner")
            val ownerName = ownerObj?.optString("display_name").orEmpty()
            SpotifyPlaylistSummary(
                id = id,
                name = name,
                coverUrl = cover,
                trackCount = total,
                owner = ownerName
            )
        }
    }
}

/**
 * Composable to embed the bridge controller in the composition
 */
@Composable
fun rememberSpotifyBridgeController(): SpotifyBridgeController {
    return remember { SpotifyBridgeControllerImpl() }
}