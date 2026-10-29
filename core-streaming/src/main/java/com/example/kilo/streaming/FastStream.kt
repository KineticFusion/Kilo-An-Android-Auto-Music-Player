@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.kilo.streaming

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object FastStream {
    private const val TAG = "VybeFast"
    private const val URL_VALIDATION_TIMEOUT_MS = 1_800
    private const val URL_VALIDATION_SUCCESS_TTL_MS = 60_000L
    private const val URL_VALIDATION_FAILURE_TTL_MS = 5_000L

    /*
    private class Client(
        val name: String,
        val version: String,
        val userAgent: String,
        val clientId: String,
        val extra: Map<String, Any>
    )

    private val clients = listOf(
        Client(
            "ANDROID_VR", "1.65.10",
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            "28",
            mapOf(
                "deviceMake" to "Oculus", "deviceModel" to "Quest 3", "osName" to "Android",
                "osVersion" to "12L", "androidSdkVersion" to 32
            )
        ),
        Client(
            "IOS", "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            "5",
            mapOf(
                "deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone",
                "osVersion" to "18.3.2.22D82"
            )
        )
    )
    */

    private val cache = ResolvedStreamCache()
    private val inflight = ConcurrentHashMap<String, FutureTask<StreamInfo?>>()
    private val preferFallbackOnNextResolve = ConcurrentHashMap<String, Boolean>()
    private data class UrlValidation(val playable: Boolean, val expiresAtMs: Long)
    private val urlValidation = ConcurrentHashMap<String, UrlValidation>()
    private val pool = Executors.newCachedThreadPool()

    fun peek(videoId: String): StreamInfo? =
        cache.get(videoId)

    fun invalidate(videoId: String) {
        cache.get(videoId)?.let { urlValidation.remove(it.url) }
        cache.invalidate(videoId)
        // If this stream failed, try the NewPipe/legacy resolver on the next
        // request instead of selecting the same fast-client URL again.
        preferFallbackOnNextResolve[videoId] = true
    }

    /** Bounded range probe used before a URL reaches Media3; successful probes are reused briefly. */
    fun validate(info: StreamInfo): Boolean = validateStreamUrl(info)

    fun warmUp() {
        pool.execute {
            runCatching {
                (URL("https://www.youtube.com/generate_204").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 2500
                }.inputStream.close()
            }
        }
    }

    fun get(videoId: String, fallback: (String) -> StreamInfo?, context: Context? = null): StreamInfo? {
        peek(videoId)?.let { cached ->
            if (validateStreamUrl(cached)) return cached
            Log.w(TAG, "resolve stage=cached_url_rejected id=$videoId; refreshing source")
            invalidate(videoId)
        }
        var owner = false
        val task = inflight.computeIfAbsent(videoId) {
            owner = true
            FutureTask<StreamInfo?>(Callable {
                resolve(videoId, fallback, AdaptiveBitrateManager.targetAudioBitrateKbps(context))?.also { resolved ->
                    cache.put(videoId, resolved, expiryOf(resolved.url))
                }
            })
        }
        if (owner) pool.execute(task)
        return try {
            task.get()
        } catch (e: Exception) {
            Log.e(TAG, "resolve failed for $videoId", e)
            null
        } finally {
            if (owner) inflight.remove(videoId, task)
        }
    }

    private fun expiryOf(url: String): Long {
        val now = System.currentTimeMillis()
        val expire = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
        return if (expire != null) {
            minOf(expire - 60_000L, now + 10_800_000L).coerceAtLeast(now)
        } else {
            now + 1_200_000L
        }
    }

    private class Client(
        val name: String,
        val version: String,
        val userAgent: String,
        val clientId: String,
        val extra: Map<String, Any>
    )

    private val clients = listOf(
        Client(
            "ANDROID_VR", "1.65.10",
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            "28",
            mapOf(
                "deviceMake" to "Oculus", "deviceModel" to "Quest 3", "osName" to "Android",
                "osVersion" to "12L", "androidSdkVersion" to 32
            )
        ),
        Client(
            "IOS", "20.10.4",
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            "5",
            mapOf(
                "deviceMake" to "Apple", "deviceModel" to "iPhone16,2", "osName" to "iPhone",
                "osVersion" to "18.3.2.22D82"
            )
        )
    )

    private fun resolve(videoId: String, fallback: (String) -> StreamInfo?, targetBitrateKbps: Int): StreamInfo? {
        if (preferFallbackOnNextResolve.remove(videoId) == true) {
            val fallbackStartedAt = System.nanoTime()
            val fallbackInfo = fallback(videoId)
            if (fallbackInfo != null && validateStreamUrl(fallbackInfo)) return fallbackInfo
            Log.w(TAG, "resolve stage=forced_fallback_miss id=$videoId durationMs=${(System.nanoTime() - fallbackStartedAt) / 1_000_000L}")
        }
        val fastStartedAt = System.nanoTime()
        val calls = clients.map { c ->
            Callable<StreamInfo> { fromClient(c, videoId, targetBitrateKbps) ?: throw IOException("${c.name}: no stream") }
        }
        val fast = try {
            pool.invokeAny(calls, 2_500, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }
        val fastDurationMs = (System.nanoTime() - fastStartedAt) / 1_000_000L
        if (fast != null && validateStreamUrl(fast)) {
            Log.d(TAG, "resolve stage=parallel_clients id=$videoId durationMs=$fastDurationMs result=ready")
            return fast
        }
        Log.w(TAG, "resolve stage=parallel_clients id=$videoId durationMs=$fastDurationMs result=${if (fast == null) "miss" else "url_rejected"}; entering repository fallback")
        val fallbackStartedAt = System.nanoTime()
        val fallbackInfo = fallback(videoId)
        val result = fallbackInfo?.takeIf(::validateStreamUrl)
        return result.also {
            Log.i(
                TAG,
                "resolve stage=repository_fallback id=$videoId durationMs=${(System.nanoTime() - fallbackStartedAt) / 1_000_000L} " +
                    "result=${if (result == null) "miss" else "ready"}"
            )
        }
    }

    private fun validateStreamUrl(info: StreamInfo): Boolean {
        if (info.url.isBlank()) return false
        val now = System.currentTimeMillis()
        urlValidation[info.url]?.takeIf { it.expiresAtMs > now }?.let { return it.playable }

        var responseCode = -1
        val startedAt = System.nanoTime()
        val playable = try {
            val streamUri = Uri.parse(info.url)
            val declaredLength = streamUri.getQueryParameter("clen")?.toLongOrNull()
            val durationMs = info.durationMs.takeIf { it > 0L }
                ?: streamUri.getQueryParameter("dur")?.toDoubleOrNull()?.let { (it * 1_000.0).toLong() }
            val estimatedLength = declaredLength ?: durationMs?.takeIf { it > 0L }
                ?.let { it * info.bitrateKbps.coerceAtLeast(1) * 1_000L / 8_000L }
            // Probe the first byte range that playback will reach after its small warm
            // buffer. Some signed URLs accept byte 0 but reject later ranges with 403,
            // causing an audible stall about a minute into the track.
            val probePosition = if (estimatedLength == null || estimatedLength > 1_048_578L) {
                1_048_576L
            } else 0L
            val connection = (URL(info.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = URL_VALIDATION_TIMEOUT_MS
                readTimeout = URL_VALIDATION_TIMEOUT_MS
                instanceFollowRedirects = true
                useCaches = false
                info.userAgent.takeIf(String::isNotBlank)?.let { setRequestProperty("User-Agent", it) }
                info.requestHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
                val host = url.host.orEmpty()
                if ((host.endsWith("youtube.com") || host.endsWith("googlevideo.com")) &&
                    getRequestProperty("Referer").isNullOrBlank()
                ) {
                    setRequestProperty("Referer", "https://www.youtube.com/")
                }
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Range", "bytes=$probePosition-${probePosition + 1L}")
            }
            try {
                responseCode = connection.responseCode
                val contentRange = connection.getHeaderField("Content-Range").orEmpty()
                val returnedStart = Regex("(?i)^bytes\\s+(\\d+)-")
                    .find(contentRange)?.groupValues?.getOrNull(1)?.toLongOrNull()
                val rangeAccepted = responseCode != 206 || returnedStart == probePosition
                val accepted = responseCode in 200..299 && rangeAccepted
                val body = if (accepted) connection.inputStream else connection.errorStream
                body?.use { if (accepted) it.read() else Unit }
                accepted
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            Log.d(TAG, "stream URL validation failed (${error.javaClass.simpleName})")
            false
        }
        val ttl = if (playable) URL_VALIDATION_SUCCESS_TTL_MS else URL_VALIDATION_FAILURE_TTL_MS
        urlValidation[info.url] = UrlValidation(playable, now + ttl)
        if (urlValidation.size > 256) {
            urlValidation.entries.removeIf { it.value.expiresAtMs <= now }
            while (urlValidation.size > 256) urlValidation.keys.firstOrNull()?.let(urlValidation::remove) ?: break
        }
        Log.i(
            TAG,
            "resolve stage=url_validation durationMs=${(System.nanoTime() - startedAt) / 1_000_000L} " +
                "http=$responseCode rangeProbe=midstream result=${if (playable) "playable" else "rejected"}"
        )
        return playable
    }

    private fun fromClient(client: Client, videoId: String, targetBitrateKbps: Int): StreamInfo? {
        val conn = (URL("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 3500
            readTimeout = 4500
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", client.userAgent)
            setRequestProperty("X-YouTube-Client-Name", client.clientId)
            setRequestProperty("X-YouTube-Client-Version", client.version)
        }
        val clientJson = JSONObject().apply {
            put("clientName", client.name)
            put("clientVersion", client.version)
            put("hl", "en")
            put("gl", "IN")
            client.extra.forEach { (k, v) -> put(k, v) }
        }
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().put("client", clientJson))
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
        if (conn.responseCode !in 200..299) {
            conn.errorStream?.close()
            return null
        }
        val res = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        if (res.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val formats = res.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: return null

        data class AudioCandidate(val url: String, val rank: Int, val bitrate: Int, val label: String)
        val candidates = mutableListOf<AudioCandidate>()
        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            val mime = f.optString("mimeType")
            val url = f.optString("url")
            if (url.isEmpty() || !mime.startsWith("audio/")) continue
            val bitrate = f.optInt("bitrate", 0)
            val rank = when {
                mime.startsWith("audio/mp4") -> 2
                mime.contains("opus") -> 1
                else -> 0
            }
            val kbps = (bitrate / 1000).coerceAtLeast(48)
            val label = if (rank == 1) "Opus • 48.0 kHz • $kbps kbps • Stereo"
            else "AAC • 44.1 kHz • $kbps kbps • Stereo"
            candidates += AudioCandidate(url, rank, bitrate, label)
        }
        val ceiling = targetBitrateKbps * 1_000
        val selected = candidates.filter { it.bitrate <= ceiling }
            .maxWithOrNull(compareBy<AudioCandidate> { it.bitrate }.thenBy { it.rank })
            ?: candidates.maxByOrNull { it.rank }
        return selected?.let {
            StreamInfo(it.url, it.label, client.userAgent, bitrateKbps = it.bitrate / 1_000)
        }
    }
}

object AudioCache {
    private var cache: SimpleCache? = null

    @Synchronized
    fun get(ctx: Context): SimpleCache =
        cache ?: SimpleCache(
            File(ctx.applicationContext.cacheDir, "vybe_audio"),
            LeastRecentlyUsedCacheEvictor(268_435_456L),
            StandaloneDatabaseProvider(ctx.applicationContext)
        ).also { cache = it }

    fun keyFor(videoId: String, url: String): String {
        val itag = Regex("[?&]itag=(\\d+)").find(url)?.groupValues?.get(1) ?: "x"
        val hostHash = "%08x".format(Uri.parse(url).host.orEmpty().hashCode())
        // Version the full-stream key so old partial-prefetch entries cannot be
        // mistaken for complete audio after an app update.
        return "$videoId-$hostHash-$itag-full-v3"
    }

    fun dataSourceFactory(
        ctx: Context,
        userAgent: String,
        requestHeaders: Map<String, String> = emptyMap()
    ): CacheDataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(5000)
            .setReadTimeoutMs(8000)
            .setDefaultRequestProperties(requestHeaders)
        return CacheDataSource.Factory()
            .setCache(get(ctx))
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun prefetch(ctx: Context, videoId: String, info: StreamInfo, bytes: Long = 1_048_576L): Throwable? =
        runCatching {
            val host = Uri.parse(info.url).host.orEmpty()
            val referer = if (host.endsWith("youtube.com") || host.endsWith("googlevideo.com")) {
                mapOf(
                    "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com",
                    "Accept-Encoding" to "identity"
                )
            } else emptyMap()
            val spec = DataSpec.Builder()
                .setUri(Uri.parse(info.url))
                .setKey(keyFor(videoId, info.url))
                .setPosition(0)
                .setLength(bytes)
                .build()
            CacheWriter(
                dataSourceFactory(ctx, info.userAgent, info.requestHeaders + referer).createDataSource(),
                spec,
                null,
                null
            ).cache()
        }.exceptionOrNull()
}

object ChunkedDownloader {
    fun contentLength(url: String, userAgent: String): Long {
        Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
        return runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", userAgent)
                            setRequestProperty("Accept-Encoding", "identity")
                            setRequestProperty("Referer", "https://www.youtube.com/")
                setRequestProperty("Range", "bytes=0-0")
            }
            val total = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
            c.inputStream.close()
            total
        }.getOrDefault(-1L)
    }

    fun download(url: String, userAgent: String, total: Long, onProgress: (Float) -> Unit): ByteArray {
        val parts = if (total < 1_000_000L) 3 else 6
        val chunk = (total + parts - 1) / parts
        val out = ByteArray(total.toInt())
        val received = AtomicLong(0)
        val lastReport = AtomicLong(0)
        val pool = Executors.newFixedThreadPool(parts)
        try {
            val futures = (0 until parts).map { i ->
                pool.submit(Callable<Unit> {
                    val start = i * chunk
                    val end = minOf(total - 1, start + chunk - 1)
                    if (start <= end) {
                        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 6000
                            readTimeout = 12000
                            setRequestProperty("User-Agent", userAgent)
                            setRequestProperty("Accept-Encoding", "identity")
                            setRequestProperty("Referer", "https://www.youtube.com/")
                            setRequestProperty("Range", "bytes=$start-$end")
                        }
                        if (conn.responseCode != 206) throw IOException("Range request HTTP ${conn.responseCode}")
                        var pos = start.toInt()
                        conn.inputStream.use { input ->
                            val buf = ByteArray(65536)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                System.arraycopy(buf, 0, out, pos, n)
                                pos += n
                                val done = received.addAndGet(n.toLong())
                                val now = System.currentTimeMillis()
                                if (now - lastReport.get() >= 120) {
                                    lastReport.set(now)
                                    onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                })
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        return out
    }
}
