package com.example.kilo.streaming

data class StreamInfo(
    val url: String,
    val codec: String,
    val userAgent: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val bitrateKbps: Int = 0,
    val durationMs: Long = 0L,
    val expiresAtEpochMs: Long = 0L
)
