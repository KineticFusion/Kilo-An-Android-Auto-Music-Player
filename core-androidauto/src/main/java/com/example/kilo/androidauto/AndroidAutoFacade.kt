package com.example.kilo.androidauto

import androidx.media3.common.MediaItem

/** Small presentation-facing contract for Android Auto browse sections. */
object AndroidAutoFacade {
    const val HOME = "tab_home"
    const val RADIO = "tab_radio"
    const val MOODS = "tab_moods"
    const val GENRES = "tab_genres"
    const val LIBRARY = "tab_library"
    const val DOWNLOADS = "tab_offline"
    const val SEARCH = "tab_search"
    const val RECENT = "tab_recent"
    const val QUICK_PICKS = "tab_picks"

    /** Only the small set of useful entry points is warmed when the car connects. */
    val warmSections: List<String> = listOf(HOME, RADIO, MOODS, RECENT, QUICK_PICKS)

    /** Keeps browse output bounded for defensive callers without changing item identity. */
    fun page(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (page < 0 || pageSize <= 0 || items.isEmpty()) return emptyList()
        val start = page.toLong() * pageSize.toLong()
        if (start >= items.size) return emptyList()
        val end = (start + pageSize).coerceAtMost(items.size.toLong())
        return items.subList(start.toInt(), end.toInt())
    }
}
