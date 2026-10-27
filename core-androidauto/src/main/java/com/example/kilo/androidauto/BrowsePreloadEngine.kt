package com.example.kilo.androidauto

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Schedules cached browse-tree preparation away from Android Auto callbacks. */
object BrowsePreloadEngine {
    private const val PREFS = "kilo_android_auto_browse"
    private const val LAST_OPENED_KEY = "last_opened_parent"
    private const val MAX_PARENT_ID_LENGTH = 256
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var lastRememberedParent: String? = null
    @Volatile private var lastRememberedAtMs: Long = 0L

    private val startupParents = AndroidAutoFacade.warmSections

    @Synchronized
    fun rememberOpened(context: Context, parentId: String) {
        val safeId = parentId.trim().take(MAX_PARENT_ID_LENGTH)
        if (safeId.isBlank() || safeId == "root") return
        val now = android.os.SystemClock.elapsedRealtime()
        if (safeId == lastRememberedParent && now - lastRememberedAtMs < 5_000L) return
        lastRememberedParent = safeId
        lastRememberedAtMs = now
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LAST_OPENED_KEY, safeId)
            .apply()
    }

    fun lastOpened(context: Context): String? = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(LAST_OPENED_KEY, null)
        ?.trim()
        ?.takeIf(String::isNotBlank)

    fun preload(context: Context, executor: Executor, loadCachedBrowseData: (String) -> Unit) {
        val last = lastOpened(context)
        (startupParents + listOfNotNull(last)).distinct().forEach { parentId ->
            if (!inFlight.add(parentId)) return@forEach
            executor.execute {
                try {
                    loadCachedBrowseData(parentId)
                } catch (_: Throwable) {
                    // Browse remains available through the normal callback fallback path.
                } finally {
                    inFlight.remove(parentId)
                }
            }
        }
    }
}
