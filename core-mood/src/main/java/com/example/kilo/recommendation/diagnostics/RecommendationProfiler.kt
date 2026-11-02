package com.example.kilo.recommendation.diagnostics

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

data class RecommendationProfile(val candidateCount: Int, val durationMs: Long, val selectedIds: List<String>)

object RecommendationProfiler {
    private val calls = AtomicLong()
    private val totalDuration = AtomicLong()
    private val totalCandidates = AtomicLong()

    fun record(count: Int, duration: Long, ids: List<String>) = RecommendationProfile(count, duration, ids).also {
        calls.incrementAndGet()
        totalDuration.addAndGet(duration.coerceAtLeast(0L))
        totalCandidates.addAndGet(count.coerceAtLeast(0).toLong())
        Log.d("GenomeX", "rank candidates=$count elapsedMs=$duration selected=${ids.size}")
    }

    fun totalCalls() = calls.get()
    fun snapshot() = mapOf(
        "rankingCalls" to calls.get(),
        "averageDurationMs" to if (calls.get() == 0L) 0L else totalDuration.get() / calls.get(),
        "averageCandidateCount" to if (calls.get() == 0L) 0L else totalCandidates.get() / calls.get()
    )
}
