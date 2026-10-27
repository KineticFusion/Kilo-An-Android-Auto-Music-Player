package com.example.kilo.buffermanager

import com.example.kilo.networkmonitor.NetworkGrade
import com.example.kilo.networkmonitor.NetworkHealthMonitor

/**
 * Advises the reliability wrapper how far ahead to prefetch. It does not rebuild the player
 * or alter Media3 LoadControl, so the active playback configuration remains untouched.
 */
data class AdaptiveBufferPlan(
    val prefetchAhead: Int,
    val targetAheadSeconds: Int,
    val networkGrade: NetworkGrade,
    val recentUnderrun: Boolean
)

object AdaptiveBufferManager {
    @Volatile private var lastUnderrunAtMs = 0L

    fun recordUnderrun(nowMs: Long = System.currentTimeMillis()) {
        lastUnderrunAtMs = nowMs
    }

    fun plan(nowMs: Long = System.currentTimeMillis()): AdaptiveBufferPlan {
        val network = NetworkHealthMonitor.snapshot()
        val base = when (network.grade) {
            NetworkGrade.EXCELLENT -> 4 to 30
            NetworkGrade.GOOD -> 3 to 35
            NetworkGrade.MODERATE -> 2 to 45
            NetworkGrade.POOR -> 1 to 55
            NetworkGrade.CRITICAL -> 0 to 0
        }
        val recentlyUnstable = lastUnderrunAtMs > 0L && nowMs - lastUnderrunAtMs < 180_000L
        val depth = if (recentlyUnstable && base.first > 0) (base.first + 1).coerceAtMost(4) else base.first
        return AdaptiveBufferPlan(depth, base.second, network.grade, recentlyUnstable)
    }
}
