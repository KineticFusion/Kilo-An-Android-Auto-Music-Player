package com.example.kilo.playback.background

import android.util.Log

/** Small diagnostics for the existing MediaLibraryService-owned playback session. */
object BackgroundPlaybackMetrics {
    private const val TAG = "KiloBackground"

    fun event(name: String, details: String = "") {
        Log.i(TAG, if (details.isBlank()) name else "$name $details")
    }

    fun heartbeat(details: String) {
        Log.d(TAG, "heartbeat $details")
    }
}
