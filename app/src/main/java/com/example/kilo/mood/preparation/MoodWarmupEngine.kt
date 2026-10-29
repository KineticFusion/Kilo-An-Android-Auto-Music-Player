package com.example.kilo.mood.preparation

import android.content.Context
import com.example.kilo.SongItemClean

/** Mood fast lane: index up to twenty songs and prepare them through the shared pipeline. */
object MoodWarmupEngine {
    fun prepare(context: Context, mood: String, results: List<SongItemClean>) {
        val visible = results.distinctBy { it.videoId.ifBlank { it.id } }.take(20)
        MoodSessionCache.remember(mood, visible.map { it.videoId.ifBlank { it.id } })
        MoodPreparationManager.prepare(context.applicationContext, mood, visible)
    }
}
