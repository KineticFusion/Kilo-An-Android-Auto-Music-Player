package com.example.kilo.mood.preparation

import android.content.Context
import com.example.kilo.PlaybackPerformanceLayer
import com.example.kilo.SongItemClean

object MoodPreparationManager {
    fun prepare(context: Context, mood: String, songs: List<SongItemClean>) {
        if (songs.isEmpty()) return
        PlaybackPerformanceLayer.prepareVisible(
            context.applicationContext,
            songs.distinctBy { it.videoId.ifBlank { it.id } }.take(20),
            "mood:$mood"
        )
    }
}
