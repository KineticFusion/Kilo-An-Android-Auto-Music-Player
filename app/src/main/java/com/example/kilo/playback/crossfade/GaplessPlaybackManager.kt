package com.example.kilo.playback.crossfade

import androidx.media3.common.Player
import com.example.kilo.SongItemClean

object GaplessPlaybackManager {
    fun shouldUseGapless(current: SongItemClean, next: SongItemClean): Boolean =
        TransitionPolicy.decide(current, next, CrossfadeSettings.DEFAULT_DURATION_MS, smart = true, gapless = true).mode == TransitionMode.GAPLESS

    /** Keeps the existing playlist path intact; Media3 advances the queued item without a stop/prepare cycle. */
    fun playlistReady(player: Player): Boolean = player.mediaItemCount > 1 && player.currentMediaItemIndex >= 0

    fun validatePlan(plan: TransitionPlan, player: Player): TransitionPlan =
        if (plan.mode == TransitionMode.GAPLESS && !playlistReady(player)) {
            TransitionPlan(TransitionMode.NATURAL, 0L, "gapless_queue_not_ready")
        } else plan
}
