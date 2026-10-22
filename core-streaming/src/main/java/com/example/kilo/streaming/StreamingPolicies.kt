package com.example.kilo.streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

data class BufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val startBufferMs: Int,
    val rebufferMs: Int
)

/** Selects a bitrate ceiling from the active link's reported capacity. */
object AdaptiveBitrateManager {
    fun targetAudioBitrateKbps(context: Context?): Int {
        if (context == null) return 192
        val capabilities = activeCapabilities(context) ?: return 96
        val measuredKbps = capabilities.linkDownstreamBandwidthKbps
        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (measuredKbps <= 0) return if (wifi) 320 else 128

        val safeCapacity = (measuredKbps * 0.65f).toInt()
        return when {
            safeCapacity >= 320 -> 320
            safeCapacity >= 256 -> 256
            safeCapacity >= 192 -> 192
            safeCapacity >= 128 -> 128
            else -> 64
        }
    }

    private fun activeCapabilities(context: Context): NetworkCapabilities? = runCatching {
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.getNetworkCapabilities(manager.activeNetwork)
    }.getOrNull()
}

/** Chooses startup and rebuffer thresholds for the current connection. */
object BufferController {
    fun profile(context: Context): BufferProfile {
        val capabilities = runCatching {
            val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.getNetworkCapabilities(manager.activeNetwork)
        }.getOrNull() ?: return BufferProfile(15_000, 50_000, 1_000, 2_000)

        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val throughput = capabilities.linkDownstreamBandwidthKbps
        return when {
            wifi && throughput >= 8_000 -> BufferProfile(15_000, 50_000, 1_000, 2_000)
            throughput >= 4_000 -> BufferProfile(15_000, 50_000, 1_000, 2_500)
            throughput >= 1_500 -> BufferProfile(20_000, 60_000, 1_500, 3_000)
            else -> BufferProfile(30_000, 120_000, 2_500, 7_000)
        }
    }
}

/** Bounded exponential recovery schedule; the resolver invalidates failed URLs between attempts. */
object RetryManager {
    private val delaysMs = longArrayOf(0L, 1_000L, 2_000L, 5_000L, 10_000L)
    val maxAttempts: Int get() = delaysMs.size
    fun delayBeforeAttempt(attempt: Int): Long? = delaysMs.getOrNull(attempt - 1)
}

fun interface StreamSource {
    fun resolve(mediaId: String): StreamInfo?
}

fun interface StreamRepository {
    fun resolve(mediaId: String): StreamInfo?
}

class ResolveStreamUseCase(private val repository: StreamRepository) {
    operator fun invoke(mediaId: String): StreamInfo? = repository.resolve(mediaId)
}

/** Resolves through sources in priority order and isolates individual source failures. */
class FailoverManager(private val sources: List<StreamSource>) {
    fun resolve(mediaId: String): StreamInfo? {
        for (source in sources) {
            val resolved = runCatching { source.resolve(mediaId) }.getOrNull()
            if (resolved != null) return resolved
        }
        return null
    }
}

/** Repository adapter that applies the configured source priority and fallbacks. */
class FailoverStreamRepository(sources: List<StreamSource>) : StreamRepository {
    private val failover = FailoverManager(sources)
    override fun resolve(mediaId: String): StreamInfo? = failover.resolve(mediaId)
}

/** Entry point for playback requests: UI/service -> use case -> repository -> sources. */
class StreamResolver(sources: List<StreamSource>) {
    private val resolveStream = ResolveStreamUseCase(FailoverStreamRepository(sources))
    fun resolve(mediaId: String): StreamInfo? = resolveStream(mediaId)
}
