package com.example.kilo.playback.crossfade

import android.content.Context
import androidx.media3.common.Player
import com.example.kilo.SongItemClean

object CrossfadeEngine {
    fun transition(context: Context, current: SongItemClean, next: SongItemClean): TransitionPlan =
        TransitionIntelligence.decide(context, current, next)

    fun transition(context: Context, current: SongItemClean, next: SongItemClean, player: Player): TransitionPlan =
        GaplessPlaybackManager.validatePlan(TransitionIntelligence.decide(context, current, next), player)
}
