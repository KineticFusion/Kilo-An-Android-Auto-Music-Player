package com.example.kilo.playback.crossfade

import android.content.Context
import com.example.kilo.SongItemClean

object TransitionIntelligence {
    fun decide(context: Context, current: SongItemClean, next: SongItemClean): TransitionPlan =
        SmartTransitionManager.plan(context, current, next)
}
