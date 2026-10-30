package com.example.kilo.playback.resume

data class PlaybackSnapshot(
    val queue: QueueSnapshot,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
    val savedAtEpochMs: Long
)
