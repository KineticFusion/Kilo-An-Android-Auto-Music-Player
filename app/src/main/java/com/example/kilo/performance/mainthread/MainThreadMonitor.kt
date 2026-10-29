package com.example.kilo.performance.mainthread

import android.os.Build
import android.os.Looper
import android.util.Log
import com.example.kilo.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only main-thread observer; it adds diagnostics and never posts work or blocks dispatch. */
object MainThreadMonitor {
    private val started = AtomicBoolean(false)
    @Volatile private var detector: UiBlockDetector? = null

    fun start() {
        if (!BuildConfig.DEBUG || !started.compareAndSet(false, true)) return
        val monitor = UiBlockDetector()
        detector = monitor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Looper.getMainLooper().setMessageLogging(monitor)
            Log.i("KiloMainThread", "mainLooperDispatchMonitor=enabled thresholdsMs=16,50,100")
        }
    }
}
