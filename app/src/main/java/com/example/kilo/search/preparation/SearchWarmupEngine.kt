package com.example.kilo.search.preparation

import android.content.Context
import com.example.kilo.SongItemClean

/** Search fast lane: cache query results and prepare the first ten through the shared pipeline. */
object SearchWarmupEngine {
    fun prepare(context: Context, query: String, results: List<SongItemClean>) {
        SearchPreparationManager.prepare(context.applicationContext, query, results.take(10))
    }
}
