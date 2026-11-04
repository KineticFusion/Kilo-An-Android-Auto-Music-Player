package com.example.kilo.streaming

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import kotlin.math.min

/** App-provided lease lookups and resolver fallback shared by player and audio warmers. */
object StreamRecoveryDataSourceFactory {
    private data class Callbacks(
        val streamForRequest: (DataSpec) -> StreamInfo?,
        val resolveAlternative: (DataSpec, StreamInfo) -> StreamInfo?,
        val reportFailure: (StreamInfo, Int) -> Unit,
        val onAlternativesExhausted: (DataSpec) -> Unit
    )

    @Volatile private var callbacks: Callbacks? = null

    fun install(
        streamForRequest: (DataSpec) -> StreamInfo?,
        resolveAlternative: (DataSpec, StreamInfo) -> StreamInfo?,
        reportFailure: (StreamInfo, Int) -> Unit,
        onAlternativesExhausted: (DataSpec) -> Unit
    ) {
        callbacks = Callbacks(streamForRequest, resolveAlternative, reportFailure, onAlternativesExhausted)
    }

    fun wrap(upstreamFactory: DataSource.Factory, component: String): DataSource.Factory {
        // Resolve callbacks when Media3 asks for a DataSource, not when the factory is
        // created. AudioCache can retain a factory before the playback service installs
        // its recovery callbacks.
        return CallbackAwareDataSourceFactory(upstreamFactory, component)
    }

    private class CallbackAwareDataSourceFactory(
        private val upstreamFactory: DataSource.Factory,
        private val component: String
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val diagnosed = DiagnosingDataSourceFactory(upstreamFactory, component)
            val installed = callbacks ?: return diagnosed.createDataSource()
            return RefreshingHttpDataSourceFactory(
                diagnosed,
                installed.streamForRequest,
                installed.resolveAlternative,
                installed.reportFailure,
                installed.onAlternativesExhausted
            ).createDataSource()
        }
    }
}

/**
 * Recovers rejected signed-stream requests underneath CacheDataSource. The cache key and
 * logical playback position stay fixed while the HTTP lease can be changed transparently.
 */
class RefreshingHttpDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val streamForRequest: (DataSpec) -> StreamInfo?,
    private val resolveAlternative: (DataSpec, StreamInfo) -> StreamInfo?,
    private val reportFailure: (StreamInfo, Int) -> Unit,
    private val onAlternativesExhausted: (DataSpec) -> Unit
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RefreshingHttpDataSource(
        upstreamFactory,
        streamForRequest,
        resolveAlternative,
        reportFailure,
        onAlternativesExhausted
    )
}

private class RefreshingHttpDataSource(
    private val upstreamFactory: DataSource.Factory,
    private val streamForRequest: (DataSpec) -> StreamInfo?,
    private val resolveAlternative: (DataSpec, StreamInfo) -> StreamInfo?,
    private val reportFailure: (StreamInfo, Int) -> Unit,
    private val onAlternativesExhausted: (DataSpec) -> Unit
) : DataSource {
    private companion object {
        const val RANGE_CHUNK_BYTES = 512L * 1024L
        const val MAX_SOURCE_SWAPS = 2
    }

    private val transferListeners = linkedSetOf<androidx.media3.datasource.TransferListener>()
    private var originalSpec: DataSpec? = null
    private var currentSource: DataSource? = null
    private var currentStream: StreamInfo? = null
    private var logicalPosition = 0L
    private var requestedRemaining = C.LENGTH_UNSET.toLong()
    private var segmentRemaining = C.LENGTH_UNSET.toLong()
    private var totalLength = C.LENGTH_UNSET.toLong()
    private var chunked = false
    private var swapsUsed = 0
    private var sameSourceRetryPosition: Long? = null
    private var lastRefreshFailure: IOException? = null
    private var opened = false

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        transferListeners += transferListener
        currentSource?.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        close()
        originalSpec = dataSpec
        logicalPosition = dataSpec.position
        requestedRemaining = dataSpec.length
        val lease = currentStreamFor(dataSpec)
        val requestUrl = dataSpec.uri.toString()
        val useCurrentLease = lease != null && lease.url != requestUrl && sameFormat(requestUrl, lease)
        val requestSpec = if (useCurrentLease) streamSpec(dataSpec, lease!!) else dataSpec
        currentStream = when {
            lease == null -> null
            requestSpec.uri.toString() == lease.url -> lease
            else -> lease.copy(
                url = requestUrl,
                itag = UriItag.from(requestUrl) ?: -1,
                mime = UriMime.from(requestUrl).orEmpty()
            )
        }
        totalLength = currentStream?.contentLength
            ?: android.net.Uri.parse(currentStream?.url ?: dataSpec.uri.toString())
                .getQueryParameter("clen")?.toLongOrNull()
            ?: C.LENGTH_UNSET.toLong()
        swapsUsed = 0
        chunked = false
        sameSourceRetryPosition = null
        lastRefreshFailure = null

        val active = currentStream
        return try {
            val openedLength = openRaw(requestSpec)
            opened = true
            if (chunked) logicalLengthFor(dataSpec, openedLength) else openedLength
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (!isRecoverable(error.responseCode)) throw error
            active?.let { reportFailure(it, error.responseCode) }
            if (isRangeRejected(error.responseCode)) {
                tryBoundedSameSource(dataSpec, error)?.let { boundedLength ->
                    opened = true
                    return logicalLengthFor(dataSpec, boundedLength)
                }
            } else {
                backoff(error.responseCode, swapsUsed)
                try {
                    val retriedLength = openRaw(requestSpec)
                    opened = true
                    return retriedLength
                } catch (retryError: HttpDataSource.InvalidResponseCodeException) {
                    if (!isRecoverable(retryError.responseCode)) throw retryError
                    currentStream?.let { reportFailure(it, retryError.responseCode) }
                    lastRefreshFailure = retryError
                }
            }
            val recoveredLength = openAlternativeAt(dataSpec, allowBoundedRetry = true)
            opened = true
            logicalLengthFor(dataSpec, recoveredLength)
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened || length == 0) return if (length == 0) 0 else C.RESULT_END_OF_INPUT
        while (true) {
            if (requestedRemaining == 0L) return C.RESULT_END_OF_INPUT
            if (chunked && segmentRemaining == 0L) {
                if (atEnd()) return C.RESULT_END_OF_INPUT
                openNextChunk()
            }
            try {
                val allowed = if (chunked && segmentRemaining >= 0L) {
                    min(length.toLong(), segmentRemaining).toInt().coerceAtLeast(1)
                } else if (requestedRemaining >= 0L) {
                    min(length.toLong(), requestedRemaining).toInt().coerceAtLeast(1)
                } else length
                val count = currentSource?.read(buffer, offset, allowed) ?: C.RESULT_END_OF_INPUT
                if (count > 0) {
                    logicalPosition += count
                    if (requestedRemaining >= 0L) requestedRemaining -= count
                    if (segmentRemaining >= 0L) segmentRemaining -= count
                    sameSourceRetryPosition = null
                    return count
                }
                if (chunked && !atEnd()) {
                    openNextChunk()
                    continue
                }
                return C.RESULT_END_OF_INPUT
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (!isRecoverable(error.responseCode)) throw error
                currentStream?.let { reportFailure(it, error.responseCode) }
                val retryAt = currentSpecAt(logicalPosition, remainingRequestLength())
                val bounded = if (isRangeRejected(error.responseCode) && sameSourceRetryPosition != logicalPosition) {
                    sameSourceRetryPosition = logicalPosition
                    tryBoundedSameSource(retryAt, error)
                } else null
                if (bounded == null) {
                    if (isTransient(error.responseCode)) backoff(error.responseCode, swapsUsed)
                    openAlternativeAt(retryAt, allowBoundedRetry = true)
                }
                // The failed read did not return bytes, so retry it at the same logical offset.
            }
        }
    }

    override fun getUri() = currentSource?.uri ?: originalSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = currentSource?.responseHeaders.orEmpty()

    @Throws(IOException::class)
    override fun close() {
        opened = false
        runCatching { currentSource?.close() }
        currentSource = null
        originalSpec = null
        currentStream = null
        logicalPosition = 0L
        requestedRemaining = C.LENGTH_UNSET.toLong()
        segmentRemaining = C.LENGTH_UNSET.toLong()
        totalLength = C.LENGTH_UNSET.toLong()
        chunked = false
        swapsUsed = 0
        sameSourceRetryPosition = null
        lastRefreshFailure = null
    }

    private fun currentStreamFor(spec: DataSpec): StreamInfo? = runCatching { streamForRequest(spec) }.getOrNull()

    @Throws(IOException::class)
    private fun openRaw(spec: DataSpec): Long {
        closeCurrentSource()
        val source = upstreamFactory.createDataSource()
        transferListeners.forEach(source::addTransferListener)
        currentSource = source
        val openedLength = source.open(spec)
        segmentRemaining = openedLength
        updateTotalLength(source.responseHeaders, spec, openedLength)
        return openedLength
    }

    /** A bounded range is used only after the provider rejects its open-ended equivalent. */
    private fun tryBoundedSameSource(spec: DataSpec, originalError: IOException): Long? {
        lastRefreshFailure = originalError
        val bounded = boundedSpec(spec) ?: return null
        return try {
            val stream = currentStream
            val openedLength = openRaw(if (stream != null) streamSpec(bounded, stream) else bounded)
            if (!rangeStartsAt(currentSource?.responseHeaders.orEmpty(), spec.position)) {
                closeCurrentSource()
                return null
            }
            chunked = true
            sameSourceRetryPosition = spec.position
            if (requestedRemaining < 0L) requestedRemaining = C.LENGTH_UNSET.toLong()
            openedLength
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            lastRefreshFailure = error
            closeCurrentSource()
            if (!isRecoverable(error.responseCode)) throw error
            currentStream?.let { reportFailure(it, error.responseCode) }
            null
        } catch (error: IOException) {
            lastRefreshFailure = error
            closeCurrentSource()
            if (Thread.currentThread().isInterrupted) throw error
            return null
        }
    }

    @Throws(IOException::class)
    private fun openAlternativeAt(spec: DataSpec, allowBoundedRetry: Boolean): Long {
        var failed = currentStream ?: currentStreamFor(spec)
            ?: throw IOException("No resolved stream lease for rejected request")
        while (swapsUsed < MAX_SOURCE_SWAPS) {
            val candidate = try {
                resolveAlternative(spec, failed)
            } catch (error: Throwable) {
                throw IOException("Alternate stream resolution failed", error)
            } ?: break
            if (candidate.sameRequestTuple(failed)) break
            swapsUsed++
            currentStream = candidate
            try {
                chunked = false
                val candidateSpec = streamSpec(spec, candidate)
                val openedLength = openRaw(candidateSpec)
                return openedLength
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (!isRecoverable(error.responseCode)) throw error
                lastRefreshFailure = error
                reportFailure(candidate, error.responseCode)
                failed = candidate
                if (isTransient(error.responseCode)) {
                    backoff(error.responseCode, swapsUsed)
                    try {
                        chunked = false
                        val retriedLength = openRaw(streamSpec(spec, candidate))
                        return retriedLength
                    } catch (retryError: HttpDataSource.InvalidResponseCodeException) {
                        if (!isRecoverable(retryError.responseCode)) throw retryError
                        lastRefreshFailure = retryError
                        reportFailure(candidate, retryError.responseCode)
                    }
                }
                if (allowBoundedRetry && isRangeRejected(error.responseCode)) {
                    val boundedLength = tryBoundedSameSource(streamSpec(spec, candidate), error)
                    if (boundedLength != null) return boundedLength
                }
            } catch (error: IOException) {
                if (Thread.currentThread().isInterrupted) throw error
                lastRefreshFailure = error
                failed = candidate
            }
        }
        onAlternativesExhausted(spec)
        throw IOException("Rejected stream exhausted after $swapsUsed alternate source(s)", lastRefreshFailure)
    }

    @Throws(IOException::class)
    private fun openNextChunk() {
        val base = currentSpecAt(logicalPosition, remainingRequestLength())
        val rangeSpec = boundedSpec(base) ?: return
        try {
            val stream = currentStream ?: throw IOException("No stream lease for next range")
            openRaw(streamSpec(rangeSpec, stream))
            if (!rangeStartsAt(currentSource?.responseHeaders.orEmpty(), logicalPosition)) {
                closeCurrentSource()
                throw IOException("Provider returned a mismatched byte range")
            }
            chunked = true
            if (segmentRemaining <= 0L) throw IOException("Empty byte-range response")
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (!isRecoverable(error.responseCode)) throw error
            lastRefreshFailure = error
            currentStream?.let { reportFailure(it, error.responseCode) }
            if (isTransient(error.responseCode)) backoff(error.responseCode, swapsUsed)
            openAlternativeAt(rangeSpec, allowBoundedRetry = isRangeRejected(error.responseCode))
            chunked = true
        }
    }

    private fun currentSpecAt(position: Long, length: Long): DataSpec {
        val base = checkNotNull(originalSpec)
        return base.buildUpon().setPosition(position).setLength(length).build()
    }

    private fun streamSpec(spec: DataSpec, stream: StreamInfo): DataSpec {
        val headers = StreamRequestPolicy.headers(stream).filterKeys { !it.equals("Range", ignoreCase = true) }
        return spec.buildUpon()
            .setUri(android.net.Uri.parse(stream.url))
            .setKey(originalSpec?.key ?: spec.key)
            .setHttpRequestHeaders(headers)
            .build()
    }

    private fun sameFormat(requestUrl: String, stream: StreamInfo): Boolean {
        val requestedItag = UriItag.from(requestUrl)
        val leaseItag = stream.itag.takeIf { it > 0 } ?: UriItag.from(stream.url)
        val requestedMime = UriMime.from(requestUrl)
        val leaseMime = stream.mime.ifBlank { UriMime.from(stream.url).orEmpty() }
        if (requestedItag != null && leaseItag != null) return requestedItag == leaseItag
        return requestedMime != null && leaseMime.isNotBlank() && requestedMime.equals(leaseMime, true)
    }

    private object UriItag {
        fun from(value: String): Int? = runCatching {
            android.net.Uri.parse(value).getQueryParameter("itag")?.toIntOrNull()
        }.getOrNull()
    }

    private object UriMime {
        fun from(value: String): String? = runCatching {
            android.net.Uri.parse(value).getQueryParameter("mime")?.substringBefore(';')
        }.getOrNull()
    }

    private fun boundedSpec(spec: DataSpec): DataSpec? {
        if (spec.length == 0L) return null
        val available = if (spec.length < 0L) RANGE_CHUNK_BYTES else min(RANGE_CHUNK_BYTES, spec.length)
        if (available <= 0L) return null
        return spec.buildUpon().setLength(available).build()
    }

    private fun remainingRequestLength(): Long =
        if (requestedRemaining < 0L) C.LENGTH_UNSET.toLong() else requestedRemaining

    private fun logicalLengthFor(spec: DataSpec, openedLength: Long): Long = when {
        spec.length >= 0L -> min(spec.length, openedLength.takeIf { it >= 0L } ?: spec.length)
        totalLength >= 0L -> (totalLength - spec.position).coerceAtLeast(0L)
        else -> C.LENGTH_UNSET.toLong()
    }

    private fun atEnd(): Boolean = requestedRemaining == 0L || (totalLength >= 0L && logicalPosition >= totalLength)

    private fun updateTotalLength(headers: Map<String, List<String>>, spec: DataSpec, openedLength: Long) {
        val contentRange = header(headers, "Content-Range")
        val total = contentRange?.substringAfterLast('/')?.toLongOrNull()
        if (total != null && total >= 0L) totalLength = total
        else if (contentRange == null && spec.position == 0L &&
            spec.length < 0L && openedLength >= 0L && !chunked
        ) {
            totalLength = openedLength
        }

        // A provider can silently cap an open-ended response. Continue it with bounded
        // ranges instead of treating the first partial response as end-of-file.
        val rangeEnd = contentRange?.let {
            Regex("(?i)^bytes\\s+\\d+-(\\d+)/(?:\\d+|\\*)$").find(it)
                ?.groupValues?.getOrNull(1)?.toLongOrNull()
        }
        if (spec.length < 0L && rangeEnd != null &&
            (total == null || rangeEnd + 1L < total)
        ) {
            chunked = true
        }
    }

    private fun rangeStartsAt(headers: Map<String, List<String>>, position: Long): Boolean {
        if (position == 0L && header(headers, "Content-Range") == null) return true
        val start = header(headers, "Content-Range")
            ?.let { Regex("(?i)^bytes\\s+(\\d+)-").find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
        return start == position
    }

    private fun header(headers: Map<String, List<String>>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    private fun closeCurrentSource() {
        runCatching { currentSource?.close() }
        currentSource = null
    }

    private fun isRangeRejected(status: Int) = status == 403 || status == 410

    private fun isTransient(status: Int) = status == 401 || status == 429

    private fun isRecoverable(status: Int) = isRangeRejected(status) || isTransient(status)

    private fun backoff(status: Int, attempt: Int) {
        val delayMs = when (status) {
            401 -> 150L
            429 -> if (attempt <= 0) 350L else 700L
            else -> 0L
        }
        if (delayMs <= 0L) return
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Stream recovery interrupted during HTTP $status backoff")
        }
    }
}
