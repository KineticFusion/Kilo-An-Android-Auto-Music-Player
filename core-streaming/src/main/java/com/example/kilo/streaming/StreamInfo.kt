package com.example.kilo.streaming

enum class StreamOrigin {
    PARALLEL_CLIENT,
    REPOSITORY_FALLBACK,
    LOSSLESS,
    UNKNOWN
}

/**
 * Complete, immutable stream lease handed from resolution through Media3 playback.
 * Existing callers may continue using the StreamInfo name during migration.
 */
data class ResolvedStream(
    val url: String,
    val codec: String,
    val userAgent: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val bitrateKbps: Int = 0,
    val durationMs: Long = 0L,
    val expiresAtEpochMs: Long = 0L,
    val videoId: String = "",
    val clientId: String = "",
    val itag: Int = -1,
    val mime: String = "",
    val contentLength: Long? = null,
    val resolvedAtMs: Long = System.currentTimeMillis(),
    val origin: StreamOrigin = StreamOrigin.UNKNOWN,
    val generation: Int = 0
) {
    val expireAtMs: Long get() = expiresAtEpochMs

    /** Equality for recovery must include every input that can change the HTTP request. */
    fun sameRequestTuple(other: ResolvedStream): Boolean =
        url == other.url && clientId == other.clientId && itag == other.itag &&
            userAgent == other.userAgent && requestHeaders == other.requestHeaders
}

typealias StreamInfo = ResolvedStream
