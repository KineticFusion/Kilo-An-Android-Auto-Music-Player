package com.example.kilo.startup

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs optional work in ordered phases after the first UI/player startup window. */
object DeferredWarmupManager {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(4),
        { task -> Thread(task, "KiloStartupWarmup").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val phaseBScheduled = AtomicBoolean(false)
    private val phaseCScheduled = AtomicBoolean(false)
    @Volatile private var launchAtMs = 0L

    fun begin(launchAtElapsedMs: Long = SystemClock.elapsedRealtime()) {
        launchAtMs = launchAtElapsedMs
        phaseBScheduled.set(false)
        phaseCScheduled.set(false)
    }

    fun schedule(phaseB: () -> Unit, phaseC: () -> Unit) {
        scheduleAt(500L, "phase_b_warmup", phaseBScheduled, phaseB)
        scheduleAt(3_000L, "phase_c_android_auto_and_heavy_work", phaseCScheduled, phaseC)
    }

    private fun scheduleAt(targetLaunchElapsedMs: Long, name: String, once: AtomicBoolean, task: () -> Unit) {
        if (!once.compareAndSet(false, true)) return
        val delayMs = (targetLaunchElapsedMs - (SystemClock.elapsedRealtime() - launchAtMs)).coerceAtLeast(0L)
        main.postDelayed({
            try {
                worker.execute {
                    LaunchProfiler.mark("${name}_started")
                    runCatching(task)
                        .onFailure { android.util.Log.w("KiloStartup", "$name failed safely", it) }
                    LaunchProfiler.mark("${name}_finished")
                }
            } catch (_: RejectedExecutionException) {
                once.set(false)
                android.util.Log.w("KiloStartup", "$name skipped because warmup queue is full")
            }
        }, delayMs)
    }
}
