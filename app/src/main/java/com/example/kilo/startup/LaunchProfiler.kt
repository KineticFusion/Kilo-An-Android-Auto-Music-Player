package com.example.kilo.startup

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** Lightweight startup markers. It records milestones only and never gates app readiness. */
object LaunchProfiler {
    private const val TAG = "KiloStartup"
    @Volatile private var launchAtMs = 0L
    private val milestones = ConcurrentHashMap<String, Long>()

    fun begin() {
        launchAtMs = SystemClock.elapsedRealtime()
        milestones.clear()
        mark("application_start")
    }

    fun mark(name: String) {
        val now = SystemClock.elapsedRealtime()
        val start = launchAtMs
        if (start == 0L || name.isBlank()) return
        val elapsed = now - start
        milestones[name] = elapsed
        Log.i(TAG, "milestone=$name launchElapsedMs=$elapsed")
    }

    fun report(): String = milestones.entries
        .sortedBy { it.value }
        .joinToString(prefix = "launchMilestones[", postfix = "]") { "${it.key}=${it.value}ms" }
}
