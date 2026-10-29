package com.example.kilo.audio.diagnostics

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

class AudioHealthMonitor {
    private val effectFailures = AtomicInteger()
    private val limiterActive = AtomicInteger()

    fun onProfileState(limiterEnabled: Boolean) {
        limiterActive.set(if (limiterEnabled) 1 else 0)
    }

    fun onEffectFailure(component: String, error: Throwable) {
        effectFailures.incrementAndGet()
        AudioMetrics.failure(component, error)
    }

    fun snapshot(): String = "limiterActive=${limiterActive.get() == 1} effectFailures=${effectFailures.get()}"
}
