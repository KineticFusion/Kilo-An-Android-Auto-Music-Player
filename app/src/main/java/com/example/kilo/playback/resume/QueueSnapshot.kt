package com.example.kilo.playback.resume

/** Lightweight queue identity persisted alongside the existing full SessionStore snapshot. */
data class QueueSnapshot(
    val mediaIds: List<String>,
    val currentIndex: Int,
    val currentMediaId: String
)
