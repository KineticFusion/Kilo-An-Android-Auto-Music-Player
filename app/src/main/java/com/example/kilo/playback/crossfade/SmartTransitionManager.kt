package com.example.kilo.playback.crossfade

import android.content.Context
import com.example.kilo.SongItemClean

object SmartTransitionManager {
    private val cachedPlans = object : LinkedHashMap<String, TransitionPlan>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TransitionPlan>?): Boolean = size > 64
    }

    fun plan(context: Context, current: SongItemClean, next: SongItemClean): TransitionPlan {
        val settings = context.applicationContext
        val duration = CrossfadeSettings.duration(settings)
        val smart = CrossfadeSettings.smartEnabled(settings)
        val gapless = CrossfadeSettings.gaplessEnabled(settings)
        val genreAware = CrossfadeSettings.genreAwareEnabled(settings)
        val intelligence = CrossfadeSettings.intelligenceEnabled(settings)
        val key = listOf(
            current.id, next.id, current.title, current.artist, current.album, current.albumBrowseId,
            next.title, next.artist, next.album, next.albumBrowseId, duration, smart, gapless, genreAware, intelligence
        ).joinToString("|")
        synchronized(cachedPlans) { cachedPlans[key]?.let { return it } }

        var plan = TransitionPolicy.decide(
            current = current,
            next = next,
            requestedDurationMs = duration,
            smart = smart && intelligence,
            gapless = gapless && intelligence,
            genreAware = genreAware
        )
        if (smart && genreAware && plan.mode == TransitionMode.CROSSFADE) {
            val genre = TrackAnalyzer.genreHint(current.title + " " + current.album)
            if (IntroDetector.likelyLongIntro(current.title, current.album)) plan = plan.copy(durationMs = (plan.durationMs / 2).coerceAtLeast(1_000L), reason = "long_intro")
            else if (OutroDetector.likelyLongOutro(current.title, current.album)) plan = plan.copy(durationMs = (plan.durationMs * 3 / 4), reason = "long_outro_${genre ?: "unknown"}")
        }
        synchronized(cachedPlans) { cachedPlans[key] = plan }
        CrossfadeMetrics.plan(current.id, plan.mode, plan.durationMs, plan.reason)
        return plan
    }
}
