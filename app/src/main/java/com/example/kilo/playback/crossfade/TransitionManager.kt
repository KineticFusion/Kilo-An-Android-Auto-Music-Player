package com.example.kilo.playback.crossfade

import android.content.Context
import androidx.media3.common.MediaItem
import com.example.kilo.PlayerHolder

class TransitionManager(private val context: Context) {
    fun plan(current: MediaItem, next: MediaItem): TransitionPlan {
        val base = CrossfadePolicy.decide(context, current, next)
        if (base.mode != TransitionMode.CROSSFADE) return base
        if (!CrossfadeConfig.read(context).intelligence) return base
        PlayerHolder.preloadTrackAnalysis(current.mediaId)
        PlayerHolder.preloadTrackAnalysis(next.mediaId)
        val analysis = com.example.kilo.audioanalysis.TransitionAnalyzer.analyze(
            PlayerHolder.cachedTrackAnalysis(current.mediaId),
            PlayerHolder.cachedTrackAnalysis(next.mediaId)
        )
        var duration = base.durationMs
        analysis.energyDifference?.let { difference ->
            if (difference >= 50.0) duration = duration.coerceAtMost(2_000L)
        }
        analysis.beatCompatibility?.let { compatibility ->
            duration = when {
                compatibility >= 0.85 -> (duration + 1_000L).coerceAtMost(8_000L)
                compatibility < 0.3 -> duration.coerceAtMost(2_500L)
                else -> duration
            }
        }
        if (analysis.incomingIntroIsSoft || analysis.outgoingOutroIsSoft) {
            duration = (duration + 1_000L).coerceAtMost(10_000L)
        }
        if (analysis.incomingNearSilent) duration = duration.coerceAtMost(1_500L)
        return base.copy(durationMs = duration, reason = "${base.reason}_analysis")
    }
}
