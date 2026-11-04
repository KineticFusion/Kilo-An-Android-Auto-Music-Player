@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.kilo.streaming

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object FastStream {
    private const val TAG = "VybeFast"
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
    private val invalidationGeneration = ConcurrentHashMap<String, AtomicLong>()
    private val clientCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private data class ClientHealth(var score: Int = 100, var changedAtMs: Long = System.currentTimeMillis(), var successes: Int = 0)
    private val clientHealth = ConcurrentHashMap<String, ClientHealth>()
    private val resolvePermits = Semaphore(3)
    private val resolveTimestamps = java.util.concurrent.ConcurrentLinkedDeque<Long>()
    private data class UrlValidation(val playable: Boolean, val expiresAtMs: Long)
    private val urlValidation = ConcurrentHashMap<String, UrlValidation>()
    private val pool = Executors.newCachedThreadPool()

    fun peek(videoId: String): StreamInfo? =
        cache.get(videoId)

    fun invalidate(videoId: String) {
        invalidationGeneration.computeIfAbsent(videoId) { AtomicLong() }.incrementAndGet()
        inflight.remove(videoId)?.cancel(true)
        cache.get(videoId)?.let { urlValidation.remove(it.url) }
        cache.invalidate(videoId)
        // If this stream failed, try the NewPipe/legacy resolver on the next
        // request instead of selecting the same fast-client URL again.
        preferFallbackOnNextResolve[videoId] = true
    }

    /** Clears an expiring URL for a proactive lease refresh without changing resolver preference. */
    fun invalidateForRefresh(videoId: String) {
        invalidationGeneration.computeIfAbsent(videoId) { AtomicLong() }.incrementAndGet()
        inflight.remove(videoId)?.cancel(true)
        cache.get(videoId)?.let { urlValidation.remove(it.url) }
        cache.invalidate(videoId)
        preferFallbackOnNextResolve.remove(videoId)
    }

    /** Bounded range probe used before a URL reaches Media3; successful probes are reused briefly. */
    fun validate(info: StreamInfo): Boolean = validateStreamUrl(info)

    /** Demotes a client that produced a stream rejected by the actual player request. */
    fun reportPlaybackFailure(info: StreamInfo, status: Int) {
        if (status !in setOf(403, 410, 429) || info.clientId.isBlank()) return
        val now = System.currentTimeMillis()
        val health = clientHealth.computeIfAbsent(healthKey(info)) { ClientHealth() }
        synchronized(health) {
            decayHealth(health, now)
            health.score = (health.score - if (status == 429) 20 else 45).coerceAtLeast(0)
            health.successes = 0
            health.changedAtMs = now
        }
        val until = now + if (status == 429) 2 * 60_000L else 15 * 60_000L
        clientCooldownUntilMs[info.clientId] = until
        Log.w(
            TAG,
            "clientDemoted client=${info.clientId} itag=${info.itag} status=$status " +
                "health=${healthScore(info)} cooldownUntilMs=$until resolvesPerMinute=${resolvesPerMinute(now)}"
        )
    }

    /** Rewards a source only after it reaches audible output, not when a probe passes. */
    fun reportPlaybackSuccess(info: StreamInfo) {
        if (info.clientId.isBlank()) return
        val now = System.currentTimeMillis()
        val health = clientHealth.computeIfAbsent(healthKey(info)) { ClientHealth() }
        val successes = synchronized(health) {
            decayHealth(health, now)
            health.successes += 1
            health.score = (health.score + if (health.successes >= 3) 12 else 5).coerceAtMost(100)
            health.changedAtMs = now
            health.successes
        }
        if (successes >= 3) clientCooldownUntilMs.remove(info.clientId)
        Log.d(TAG, "clientPlaybackSuccess client=${info.clientId} itag=${info.itag} health=${healthScore(info)}")
    }

    fun resolvesPerMinute(nowMs: Long = System.currentTimeMillis()): Int {
        val cutoff = nowMs - 60_000L
        while (true) {
            val first = resolveTimestamps.peekFirst() ?: break
            if (first >= cutoff || !resolveTimestamps.removeFirstOccurrence(first)) break
        }
        return resolveTimestamps.size
    }

    private fun healthKey(info: StreamInfo) = "${info.clientId}:${info.itag}"

    private fun healthScore(info: StreamInfo): Int {
        val record = clientHealth[healthKey(info)] ?: return 100
        synchronized(record) {
            decayHealth(record, System.currentTimeMillis())
            return record.score
        }
    }

    private fun decayHealth(record: ClientHealth, nowMs: Long) {
        val recoveryPoints = ((nowMs - record.changedAtMs).coerceAtLeast(0L) / (5 * 60_000L)).toInt()
        if (recoveryPoints > 0) {
            record.score = (record.score + recoveryPoints).coerceAtMost(100)
            record.changedAtMs = nowMs
        }
    }

    /**
     * Resolves a different request tuple after a playback rejection. It tries another
     * healthy parallel client first, then the known repository path, and never returns
     * the exact request tuple that just failed.
     */
    fun resolveAlternative(
        videoId: String,
        fallback: (String) -> StreamInfo?,
        context: Context?,
        failed: StreamInfo,
        preferredItag: String? = failed.itag.takeIf { it > 0 }?.toString()
    ): StreamInfo? {
        val failedClient = failed.clientId
        val targetBitrate = AdaptiveBitrateManager.targetAudioBitrateKbps(context)
        val candidates = clients.filter { client ->
            client.name != failedClient && (clientCooldownUntilMs[client.name] ?: 0L) <= System.currentTimeMillis()
        }
        if (candidates.isNotEmpty()) {
            val tasks = candidates.map { client ->
                Callable {
                    val candidate = fromClient(client, videoId, targetBitrate, preferredItag = preferredItag)
                    if (candidate != null && !candidate.sameRequestTuple(failed) && validateStreamUrl(candidate)) {
                        candidate
                    } else null
                }
            }
            val alternatives = runCatching {
                pool.invokeAll(tasks, 2_500, TimeUnit.MILLISECONDS)
                    .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
                    .sortedWith(
                        compareByDescending<StreamInfo> { healthScore(it) }
                            .thenByDescending { it.itag.toString() == preferredItag }
                            .thenByDescending { it.mime.startsWith("audio/mp4", ignoreCase = true) && opusClientUnhealthy(candidates) }
                    )
            }.getOrDefault(emptyList())
            alternatives.firstOrNull()?.let { alternative ->
                cache.put(videoId, alternative, expiryOf(alternative.url))
                Log.i(TAG, "resolve stage=alternate_parallel_client id=$videoId client=${alternative.clientId} itag=${alternative.itag}")
                return alternative
            }
        }

        val repository = runCatching { fallback(videoId) }.getOrNull()
            ?.let { info ->
                val marked = info.copy(videoId = videoId, origin = StreamOrigin.REPOSITORY_FALLBACK)
                marked.takeIf { !it.sameRequestTuple(failed) && validateStreamUrl(it) }
            }
        if (repository != null) {
            cache.put(videoId, repository, expiryOf(repository.url))
            Log.i(TAG, "resolve stage=alternate_repository_fallback id=$videoId itag=${repository.itag}")
            return repository
        }
        Log.w(TAG, "resolve stage=alternatives_exhausted id=$videoId")
        return null
    }

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

    fun get(
        videoId: String,
        fallback: (String) -> StreamInfo?,
        context: Context? = null,
        preferredItag: String? = null
    ): StreamInfo? {
        peek(videoId)?.let { cached ->
            if (validateStreamUrl(cached)) return cached
            Log.w(TAG, "resolve stage=cached_url_rejected id=$videoId; refreshing source")
            invalidate(videoId)
        }
        while (true) {
            val generation = invalidationGeneration.computeIfAbsent(videoId) { AtomicLong() }.get()
            var owner = false
            val task = inflight.computeIfAbsent(videoId) {
                owner = true
                FutureTask<StreamInfo?>(Callable {
                    resolve(
                        videoId,
                        fallback,
                        AdaptiveBitrateManager.targetAudioBitrateKbps(context),
                        preferredItag
                    )?.also { resolved ->
                        // A 403 may invalidate this lookup while resolver clients are still
                        // racing. Never let that old lookup repopulate the rejected URL.
                        if (invalidationGeneration[videoId]?.get() == generation) {
                            cache.put(videoId, resolved, expiryOf(resolved.url))
                        }
                    }
                })
            }
            if (owner) pool.execute(task)
            val result = try {
                task.get()
            } catch (e: Exception) {
                if (invalidationGeneration[videoId]?.get() == generation) {
                    Log.e(TAG, "resolve failed for $videoId", e)
                    return null
                }
                null
            } finally {
                if (owner) inflight.remove(videoId, task)
            }
            if (invalidationGeneration[videoId]?.get() != generation) continue
            return result
        }
    }

    private fun expiryOf(url: String): Long {
        val now = System.currentTimeMillis()
        val raw = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        val parsed = raw?.let { if (it < 10_000_000_000L) it * 1_000L else it }
        return parsed?.takeIf { it > now } ?: (now + 5 * 60 * 60_000L)
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

    private fun resolve(
        videoId: String,
        fallback: (String) -> StreamInfo?,
        targetBitrateKbps: Int,
        preferredItag: String?
    ): StreamInfo? {
        val now = System.currentTimeMillis()
        resolveTimestamps.addLast(now)
        resolvesPerMinute(now)
        val acquired = try {
            resolvePermits.tryAcquire(2_500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            Log.w(TAG, "resolveConcurrencyLimited id=$videoId activeLimit=3 resolvesPerMinute=${resolvesPerMinute(now)}")
            return runCatching { fallback(videoId) }.getOrNull()?.takeIf(::validateStreamUrl)
        }
        return try {
            resolveWithPermit(videoId, fallback, targetBitrateKbps, preferredItag)
        } finally {
            resolvePermits.release()
        }
    }

    private fun resolveWithPermit(
        videoId: String,
        fallback: (String) -> StreamInfo?,
        targetBitrateKbps: Int,
        preferredItag: String?
    ): StreamInfo? {
        if (preferFallbackOnNextResolve.remove(videoId) == true) {
            val fallbackStartedAt = System.nanoTime()
            val fallbackInfo = fallback(videoId)
            if (fallbackInfo != null && validateStreamUrl(fallbackInfo)) return fallbackInfo
            Log.w(TAG, "resolve stage=forced_fallback_miss id=$videoId durationMs=${(System.nanoTime() - fallbackStartedAt) / 1_000_000L}")
        }
        val fastStartedAt = System.nanoTime()
        val availableClients = clients.filter { (clientCooldownUntilMs[it.name] ?: 0L) <= System.currentTimeMillis() }
        val calls = availableClients.map { c ->
            Callable<StreamInfo?> {
                val candidate = fromClient(c, videoId, targetBitrateKbps, preferredItag) ?: return@Callable null
                // Validate inside each parallel resolver task. If the fastest client
                // returns a stale URL, retain successful candidates and rank by known
                // playback health before returning one.
                if (!validateStreamUrl(candidate)) return@Callable null
                candidate
            }
        }
        val fast = try {
            pool.invokeAll(calls, 2_500, TimeUnit.MILLISECONDS)
                .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
                .sortedWith(
                    compareByDescending<StreamInfo> { healthScore(it) }
                        .thenByDescending { it.itag.toString() == preferredItag }
                        .thenByDescending { it.mime.startsWith("audio/mp4", ignoreCase = true) && opusClientUnhealthy(availableClients) }
                )
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
        val fastDurationMs = (System.nanoTime() - fastStartedAt) / 1_000_000L
        if (fast != null) {
            Log.d(
                TAG,
                "resolve stage=parallel_clients id=$videoId durationMs=$fastDurationMs result=ready " +
                    "client=${fast.clientId} itag=${fast.itag} health=${healthScore(fast)} resolvesPerMinute=${resolvesPerMinute()}"
            )
            return fast
        }
        Log.w(TAG, "resolve stage=parallel_clients id=$videoId durationMs=$fastDurationMs result=miss; entering repository fallback")
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

    private fun opusClientUnhealthy(activeClients: List<Client>): Boolean = activeClients.any { client ->
        (clientCooldownUntilMs[client.name] ?: 0L) > System.currentTimeMillis() ||
            clientHealth.entries.asSequence()
                .filter { it.key.startsWith("${client.name}:") }
                .map { entry -> synchronized(entry.value) { entry.value.score } }
                .minOrNull()
                ?.let { it < 60 } == true
    }

    private fun validateStreamUrl(info: StreamInfo): Boolean {
        if (info.url.isBlank()) return false
        val now = System.currentTimeMillis()
        urlValidation[info.url]?.takeIf { it.expiresAtMs > now }?.let { return it.playable }

        val probeStatuses = mutableListOf<String>()
        val startedAt = System.nanoTime()
        val playable = try {
            val streamUri = Uri.parse(info.url)
            val declaredLength = streamUri.getQueryParameter("clen")?.toLongOrNull()
            val durationMs = info.durationMs.takeIf { it > 0L }
                ?: streamUri.getQueryParameter("dur")?.toDoubleOrNull()?.let { (it * 1_000.0).toLong() }
            val estimatedLength = declaredLength ?: durationMs?.takeIf { it > 0L }
                ?.let { it * info.bitrateKbps.coerceAtLeast(1) * 1_000L / 8_000L }
            // Use the same Media3 HTTP factory, headers, and range generation as playback.
            // A one-byte bounded probe can pass while the player's open-ended range fails;
            // position+LENGTH_UNSET deliberately produces the player's actual range style.
            val boundary = 1_048_576L
            val midpoint = estimatedLength?.takeIf { it > boundary * 2L }?.div(2L)
            val probePositions = buildList {
                add(0L)
                midpoint?.let(::add)
                if (estimatedLength == null || estimatedLength > boundary) add(boundary)
            }.distinct()
            val headers = StreamRequestPolicy.headers(info)
            probePositions.all { position ->
                val spec = DataSpec.Builder()
                    .setUri(streamUri)
                    .setHttpRequestHeaders(headers)
                    .setPosition(position)
                    .setLength(C.LENGTH_UNSET.toLong())
                    .build()
                val source: DataSource = DiagnosingDataSourceFactory(
                    StreamRequestPolicy.httpFactory(), "resolver_probe"
                ).createDataSource()
                try {
                    source.open(spec)
                    val contentRange = source.responseHeaders.entries
                        .firstOrNull { it.key.equals("Content-Range", true) }
                        ?.value?.firstOrNull().orEmpty()
                    val returnedStart = Regex("(?i)^bytes\\s+(\\d+)-")
                        .find(contentRange)?.groupValues?.getOrNull(1)?.toLongOrNull()
                    val firstByte = ByteArray(1)
                    val read = source.read(firstByte, 0, 1)
                    val accepted = read > 0 && (position == 0L || returnedStart == position)
                    probeStatuses += "offset=$position rangeStart=${returnedStart ?: "none"} accepted=$accepted"
                    accepted
                } catch (error: HttpDataSource.InvalidResponseCodeException) {
                    probeStatuses += "offset=$position http=${error.responseCode}"
                    false
                } finally {
                    runCatching { source.close() }
                }
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
                "rangeProbe=player-equivalent[$probeStatuses] result=${if (playable) "playable" else "rejected"}"
        )
        return playable
    }

    private fun fromClient(
        client: Client,
        videoId: String,
        targetBitrateKbps: Int,
        preferredItag: String?
    ): StreamInfo? {
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
        StreamRequestDiagnostics.rawRequest(
            component = "resolver_client_${client.name}",
            mediaId = videoId,
            url = conn.url.toString(),
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to client.userAgent,
                "X-YouTube-Client-Name" to client.clientId,
                "X-YouTube-Client-Version" to client.version
            )
        )
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

        data class AudioCandidate(
            val url: String,
            val itag: Int,
            val rank: Int,
            val bitrate: Int,
            val mime: String,
            val label: String
        )
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
            candidates += AudioCandidate(url, f.optInt("itag", -1), rank, bitrate, mime, label)
        }
        val ceiling = targetBitrateKbps * 1_000
        val stableFormat = preferredItag?.toIntOrNull()?.let { wanted ->
            candidates.filter { it.itag == wanted }.maxByOrNull { it.bitrate }
        }
        val selected = stableFormat ?: candidates.filter { it.bitrate <= ceiling }
            .maxWithOrNull(compareBy<AudioCandidate> { it.bitrate }.thenBy { it.rank })
            ?: candidates.maxByOrNull { it.rank }
        return selected?.let {
            StreamInfo(
                url = it.url,
                codec = it.label,
                userAgent = client.userAgent,
                bitrateKbps = it.bitrate / 1_000,
                expiresAtEpochMs = expiryOf(it.url),
                videoId = videoId,
                clientId = client.name,
                itag = it.itag,
                mime = it.mime.substringBefore(';'),
                contentLength = Uri.parse(it.url).getQueryParameter("clen")?.toLongOrNull(),
                resolvedAtMs = System.currentTimeMillis(),
                origin = StreamOrigin.PARALLEL_CLIENT
            )
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
        // The signed URL may change between leases; track and encoding stay stable.
        return "yt:$videoId:$itag"
    }

    fun dataSourceFactory(
        ctx: Context,
        userAgent: String,
        requestHeaders: Map<String, String> = emptyMap()
    ): CacheDataSource.Factory {
        val http = StreamRequestPolicy.httpFactory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(requestHeaders)
        return CacheDataSource.Factory()
            .setCache(get(ctx))
            .setUpstreamDataSourceFactory(StreamRecoveryDataSourceFactory.wrap(http, "audio_cache_warmup_or_prefetch"))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun prefetch(ctx: Context, videoId: String, info: StreamInfo, bytes: Long = 1_048_576L): Throwable? =
        runCatching {
            val headers = StreamRequestPolicy.headers(info)
            val spec = DataSpec.Builder()
                .setUri(Uri.parse(info.url))
                .setKey(keyFor(videoId, info.url))
                .setHttpRequestHeaders(headers)
                .setPosition(0)
                .setLength(bytes)
                .build()
            CacheWriter(
                dataSourceFactory(ctx, info.userAgent, headers).createDataSource(),
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
