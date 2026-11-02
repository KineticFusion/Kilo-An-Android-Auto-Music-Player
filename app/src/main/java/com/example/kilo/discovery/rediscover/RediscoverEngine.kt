package com.example.kilo.discovery.rediscover

import com.example.kilo.SongItemClean
import com.example.kilo.companion.ListeningEvent
import com.example.kilo.discovery.RediscoverPick

class RediscoverEngine(
    private val finder: ForgottenTracksFinder = ForgottenTracksFinder(),
    private val affinity: HistoricalAffinityEngine = HistoricalAffinityEngine()
) {
    fun build(catalog: List<SongItemClean>, events: List<ListeningEvent>, recentIds: Set<String>, nowMs: Long = System.currentTimeMillis()): List<RediscoverPick> {
        val groups = affinity.group(catalog, events, recentIds, nowMs)
        val forgotten = finder.find(catalog, events, recentIds, nowMs, 20)
        val allForgotten = (groups["forgotten"].orEmpty() + forgotten).distinctBy { it.id }.take(20)
        return listOfNotNull(
            RediscoverPick("rediscover_forgotten", "Forgotten Gems", "Tracks you returned to before, now out of rotation", allForgotten),
            groups["loved"].orEmpty().takeIf { it.isNotEmpty() }?.let { RediscoverPick("rediscover_loved", "Loved Last Season", "Old favourites that have been quiet lately", it) },
            groups["artists"].orEmpty().takeIf { it.isNotEmpty() }?.let { RediscoverPick("rediscover_artists", "Artists You Miss", "Artists from your past listening", it) }
        )
    }
}
