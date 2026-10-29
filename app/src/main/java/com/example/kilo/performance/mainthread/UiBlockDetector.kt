package com.example.kilo.performance.mainthread

import android.os.SystemClock
import android.util.Printer

/** Measures each Android main-looper dispatch without changing its execution. */
internal class UiBlockDetector : Printer {
    private var dispatchStartedAtNs = 0L
    private var dispatchMessage = ""

    override fun println(message: String) {
        when {
            message.startsWith(">>>>> Dispatching") -> {
                dispatchStartedAtNs = SystemClock.elapsedRealtimeNanos()
                dispatchMessage = message
            }
            message.startsWith("<<<<< Finished") -> {
                val started = dispatchStartedAtNs
                if (started == 0L) return
                val durationMs = ((SystemClock.elapsedRealtimeNanos() - started).coerceAtLeast(0L) / 1_000_000L)
                SlowOperationReporter.report(durationMs, dispatchMessage)
                dispatchStartedAtNs = 0L
                dispatchMessage = ""
            }
        }
    }
}
