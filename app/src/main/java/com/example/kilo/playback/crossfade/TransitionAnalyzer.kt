package com.example.kilo.playback.crossfade

import androidx.media3.common.MediaItem
import com.example.kilo.toSong

/** Uses cached catalog/analysis metadata only; it never blocks a transition on network analysis. */
object TransitionAnalyzer {
    fun analyze(current: MediaItem, next: MediaItem): TransitionPlan {
        val a = current.toSong()
        val b = next.toSong()
        val longForm = sequenceOf(a.title, b.title, a.album, b.album, a.artist, b.artist)
            .any { text -> listOf("live", "podcast", "audiobook", "continuous mix").any { text.contains(it, true) } }
        return if (longForm) TransitionPlan(TransitionMode.GAPLESS, 0L, "long_form_metadata")
        else TransitionPlan(TransitionMode.CROSSFADE, CrossfadeSettings.DEFAULT_DURATION_MS, "metadata_default")
    }
}
