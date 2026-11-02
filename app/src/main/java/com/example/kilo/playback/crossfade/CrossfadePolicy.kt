package com.example.kilo.playback.crossfade

import android.content.Context
import androidx.media3.common.MediaItem
import com.example.kilo.toSong

object CrossfadePolicy {
    fun decide(context: Context, current: MediaItem, next: MediaItem): TransitionPlan {
        val config = CrossfadeConfig.read(context)
        if (!config.enabled || config.durationMs <= 0L) return TransitionPlan(TransitionMode.NATURAL, 0L, "disabled")
        return TransitionPolicy.decide(
            current.toSong(), next.toSong(), config.durationMs,
            smart = config.smart && config.intelligence,
            gapless = config.gapless,
            genreAware = config.genreAware
        )
    }
}
