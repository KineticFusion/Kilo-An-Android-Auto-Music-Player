package com.example.kilo.search.preparation

import android.content.Context
import com.example.kilo.PlaybackPerformanceLayer
import com.example.kilo.SongItemClean

object SearchWarmupManager {
    fun prepare(context: Context, songs: List<SongItemClean>) {
        PlaybackPerformanceLayer.prepareVisible(context.applicationContext, songs.take(10), "search")
    }
}
