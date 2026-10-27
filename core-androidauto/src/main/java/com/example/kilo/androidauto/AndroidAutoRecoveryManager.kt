package com.example.kilo.androidauto

import android.util.Log

/** Keeps browse/artwork helpers on cached fallbacks when a lightweight operation fails. */
object AndroidAutoRecoveryManager {
    private const val TAG = "KiloAutoRecovery"

    fun <T> fallback(component: String, defaultValue: T, operation: () -> T): T = try {
        operation()
    } catch (failure: OutOfMemoryError) {
        AndroidAutoCache.trim()
        Log.e(TAG, "$component exhausted memory; using fallback", failure)
        defaultValue
    } catch (failure: Exception) {
        Log.w(TAG, "$component failed; using fallback", failure)
        defaultValue
    }

    fun onMemoryPressure() {
        AndroidAutoCache.trim()
    }
}
