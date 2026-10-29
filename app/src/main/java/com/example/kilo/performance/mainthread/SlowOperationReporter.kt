package com.example.kilo.performance.mainthread

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/** Reports main-looper dispatches that exceed the architecture's 16/50/100 ms budgets. */
object SlowOperationReporter {
    private const val TAG = "KiloMainThread"
    private val counts = longArrayOf(0L, 0L, 0L)
    private val lastLogs = longArrayOf(0L, 0L, 0L)

    @Synchronized
    fun report(durationMs: Long, message: String) {
        val bucket = when {
            durationMs >= 100L -> 2
            durationMs >= 50L -> 1
            durationMs > 16L -> 0
            else -> return
        }
        counts[bucket]++
        val now = SystemClock.elapsedRealtime()
        val throttleMs = when (bucket) { 2 -> 1_000L; 1 -> 2_000L; else -> 5_000L }
        if (now - lastLogs[bucket] < throttleMs) return
        lastLogs[bucket] = now
        val label = when (bucket) { 2 -> "block_100ms"; 1 -> "slow_50ms"; else -> "frame_16ms" }
        val log = "mainThreadDispatch=$label durationMs=$durationMs count=${counts[bucket]} message=${message.take(180)}"
        when (bucket) {
            2 -> Log.e(TAG, log)
            1 -> Log.w(TAG, log)
            else -> Log.d(TAG, log)
        }
    }
}
