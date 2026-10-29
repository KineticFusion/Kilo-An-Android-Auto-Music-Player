package com.example.kilo.search.preparation

import android.content.Context
import com.example.kilo.SongItemClean

object SearchPreparationManager {
    fun prepare(context: Context, query: String, songs: List<SongItemClean>) {
        val distinct = songs.distinctBy { it.videoId.ifBlank { it.id } }
        SearchResultCache.put(query, distinct)
        SearchWarmupManager.prepare(context.applicationContext, distinct.take(10))
    }
}
