package com.example.kilo.audio.diagnostics

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

object AudioMetrics {
    private const val TAG = "KiloAudioEngine"
    private val applyCount = AtomicLong()
    private val failureCount = AtomicLong()

    fun profileApplied(route: String, profile: String, sessionId: Int, limiter: Boolean, effects: String) {
        applyCount.incrementAndGet()
        Log.i(TAG, "audio_profile route=$route profile=$profile session=$sessionId limiter=$limiter effects=$effects at=${SystemClock.elapsedRealtime()}")
    }

    fun failure(component: String, error: Throwable) {
        failureCount.incrementAndGet()
        Log.w(TAG, "audio_effect_unavailable component=$component reason=${error.javaClass.simpleName}:${error.message}")
    }

    fun counters(): Pair<Long, Long> = applyCount.get() to failureCount.get()
}
