package com.example.kilo.streaming

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import java.nio.charset.StandardCharsets
import java.util.Date

/** Debug-only, credential-safe request tracing shared by resolver probes and playback HTTP. */
object StreamRequestDiagnostics {
    private const val TAG = "KiloStreamHttp"

    @Volatile
    var enabled: Boolean = false

    fun request(component: String, dataSpec: DataSpec) {
        if (!enabled) return
        val uri = dataSpec.uri
        val headers = dataSpec.httpRequestHeaders
        val userAgent = header(headers, "User-Agent")
        val range = header(headers, "Range") ?: rangeFor(dataSpec)
        val query = Uri.parse(uri.toString())
        val expiry = query.getQueryParameter("expire")?.toLongOrNull()?.let { value ->
            val epochMs = if (value < 10_000_000_000L) value * 1_000L else value
            "${value}(epochMs=$epochMs,at=${Date(epochMs)})"
        } ?: "absent"
        Log.d(
            TAG,
            "request videoId=${mediaId(dataSpec)} component=$component host=${uri.host.orEmpty()} " +
                "query={c=${query.getQueryParameter("c") ?: "absent"}," +
                "itag=${query.getQueryParameter("itag") ?: "absent"}," +
                "mime=${query.getQueryParameter("mime") ?: "absent"}," +
                "clen=${query.getQueryParameter("clen") ?: "absent"}," +
                "expire=$expiry,ip=${query.getQueryParameter("ip") ?: "absent"}," +
                "ratebypass=${query.getQueryParameter("ratebypass") ?: "absent"}," +
                "nPresent=${query.getQueryParameter("n") != null}," +
                "potPresent=${query.getQueryParameter("pot") != null}," +
                "sigPresent=${query.getQueryParameter("sig") != null}," +
                "lsigPresent=${query.getQueryParameter("lsig") != null}} " +
                "method=${method(dataSpec.httpMethod)} headers=${headers.keys.sorted()} " +
                "userAgent=${userAgent ?: "default"} range=${range ?: "none"} " +
                "position=${dataSpec.position} length=${dataSpec.length}"
        )
    }

    fun rawRequest(
        component: String,
        mediaId: String,
        url: String,
        method: String,
        headers: Map<String, String>,
        range: String? = null
    ) {
        if (!enabled) return
        val uri = Uri.parse(url)
        val query = uri
        val expiry = query.getQueryParameter("expire")?.toLongOrNull()?.let { value ->
            val epochMs = if (value < 10_000_000_000L) value * 1_000L else value
            "${value}(epochMs=$epochMs,at=${Date(epochMs)})"
        } ?: "absent"
        Log.d(
            TAG,
            "request videoId=$mediaId component=$component host=${uri.host.orEmpty()} " +
                "query={c=${query.getQueryParameter("c") ?: "absent"}," +
                "itag=${query.getQueryParameter("itag") ?: "absent"}," +
                "mime=${query.getQueryParameter("mime") ?: "absent"}," +
                "clen=${query.getQueryParameter("clen") ?: "absent"}," +
                "expire=$expiry,ip=${query.getQueryParameter("ip") ?: "absent"}," +
                "ratebypass=${query.getQueryParameter("ratebypass") ?: "absent"}," +
                "nPresent=${query.getQueryParameter("n") != null}," +
                "potPresent=${query.getQueryParameter("pot") != null}," +
                "sigPresent=${query.getQueryParameter("sig") != null}," +
                "lsigPresent=${query.getQueryParameter("lsig") != null}} " +
                "method=$method headers=${headers.keys.sorted()} " +
                "userAgent=${header(headers, "User-Agent") ?: "default"} range=${range ?: "none"}"
        )
    }

    fun response(
        component: String,
        dataSpec: DataSpec,
        status: String,
        headers: Map<String, List<String>>,
        errorBody: ByteArray? = null
    ) {
        if (!enabled) return
        val contentRange = header(headers, "Content-Range")
        val contentLength = header(headers, "Content-Length")
        val contentType = header(headers, "Content-Type")
        val safeBody = errorBody?.let(::redact)?.take(200)
        Log.d(
            TAG,
            "response videoId=${mediaId(dataSpec)} component=$component host=${dataSpec.uri.host.orEmpty()} " +
                "status=$status contentRange=${contentRange ?: "absent"} " +
                "contentLength=${contentLength ?: "absent"} contentType=${contentType ?: "absent"}" +
                (safeBody?.let { " errorBody=$it" } ?: "")
        )
    }

    fun inferredSuccessStatus(headers: Map<String, List<String>>): String =
        if (header(headers, "Content-Range") != null) "206(inferred)" else "opened(status-unavailable)"

    fun mediaId(dataSpec: DataSpec): String {
        val uri = dataSpec.uri
        if (uri.scheme.equals("vybe", true)) return uri.lastPathSegment.orEmpty()
        val key = dataSpec.key.orEmpty()
        if (key.startsWith("yt:")) return key.removePrefix("yt:").substringBefore(':')
        // Current on-disk cache key is kept stable during the migration so existing warm
        // cache entries remain usable; parse that key as a fallback for request tracing.
        return Regex("^(.+?)(?:-[0-9a-fA-F]{8})?-[^-]+-full-v\\d+$")
            .matchEntire(key)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun method(value: Int): String = when (value) {
        DataSpec.HTTP_METHOD_GET -> "GET"
        DataSpec.HTTP_METHOD_POST -> "POST"
        DataSpec.HTTP_METHOD_HEAD -> "HEAD"
        else -> "method_$value"
    }

    private fun rangeFor(dataSpec: DataSpec): String? {
        if (dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()) return null
        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else
            (dataSpec.position + dataSpec.length - 1L).toString()
        return "bytes=${dataSpec.position}-$end"
    }

    private fun header(headers: Map<String, *>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.let { value ->
                when (value) {
                    is List<*> -> value.joinToString()
                    else -> value.toString()
                }
            }

    private fun redact(bytes: ByteArray): String {
        val text = String(bytes, StandardCharsets.UTF_8)
            .replace(Regex("[\\r\\n\\t]+"), " ")
        return text
            .replace(Regex("(?i)(authorization|cookie|token|signature|secret|key)(\\s*[=:]\\s*)[^,;\\s\\\"']+"), "$1$2[REDACTED]")
            .replace(Regex("(?i)https?://[^\\s\\\"']+"), "[URL REDACTED]")
    }
}

/** Shared HTTP behavior and headers for the resolver probe, cache warmer, and player. */
object StreamRequestPolicy {
    fun httpFactory(): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(6_000)
        .setReadTimeoutMs(30_000)

    fun headers(info: StreamInfo): Map<String, String> {
        val host = Uri.parse(info.url).host.orEmpty()
        val youtubeHost = host.endsWith("youtube.com") || host.endsWith("googlevideo.com")
        return buildMap {
            putAll(info.requestHeaders)
            if (youtubeHost) {
                put("Referer", "https://www.youtube.com/")
                put("Origin", "https://www.youtube.com")
                put("Accept-Encoding", "identity")
            }
            if (info.userAgent.isNotBlank()) put("User-Agent", info.userAgent)
        }
    }
}

/** Wraps a Media3 source so debug builds can compare probe, cache-warm, and playback requests. */
class DiagnosingDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val component: String
) : DataSource.Factory {
    override fun createDataSource(): DataSource = DiagnosingDataSource(upstreamFactory.createDataSource(), component)
}

private class DiagnosingDataSource(
    private val upstream: DataSource,
    private val component: String
) : DataSource {
    private var activeSpec: DataSpec? = null

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        activeSpec = dataSpec
        StreamRequestDiagnostics.request(component, dataSpec)
        return try {
            upstream.open(dataSpec).also {
                StreamRequestDiagnostics.response(
                    component,
                    dataSpec,
                    StreamRequestDiagnostics.inferredSuccessStatus(upstream.responseHeaders),
                    upstream.responseHeaders
                )
            }
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            StreamRequestDiagnostics.response(
                component,
                dataSpec,
                error.responseCode.toString(),
                error.headerFields,
                errorBody(error)
            )
            throw error
        } catch (error: Throwable) {
            if (StreamRequestDiagnostics.enabled) {
                android.util.Log.w("KiloStreamHttp", "openFailure component=$component videoId=${StreamRequestDiagnostics.mediaId(dataSpec)} type=${error.javaClass.simpleName}")
            }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri() = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        activeSpec = null
        upstream.close()
    }

    private fun errorBody(error: HttpDataSource.InvalidResponseCodeException): ByteArray? = runCatching {
        error.javaClass.fields.firstOrNull { it.name == "responseBody" }
            ?.get(error) as? ByteArray
    }.getOrNull()
}
