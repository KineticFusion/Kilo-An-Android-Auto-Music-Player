package com.example.kilo.discovery.continuation

import com.example.kilo.SongItemClean

class ContinuationQueueBuilder(private val engine: ContinuationEngine = ContinuationEngine()) {
    fun build(playlist: List<SongItemClean>, candidates: List<SongItemClean>, seenIds: Set<String>, excludeIds: Set<String> = emptySet()): List<SongItemClean> =
        engine.generate(playlist, candidates, seenIds, excludeIds, count = 10)
}
