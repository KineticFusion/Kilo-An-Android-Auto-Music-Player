package com.example.kilo.playback.preparation

import android.util.Log

object StreamPreparationMetrics {
    fun prepared(trackId: String, elapsedMs: Long, bufferedMs: Long) =
        Log.i("KiloCrossfade", "track_prepared id=$trackId elapsedMs=$elapsedMs bufferedMs=$bufferedMs")
    fun failed(trackId: String, elapsedMs: Long, reason: String) =
        Log.w("KiloCrossfade", "track_prepare_failed id=$trackId elapsedMs=$elapsedMs reason=$reason")
}
