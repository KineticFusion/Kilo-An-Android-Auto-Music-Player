package com.example.kilo.discovery.continuation

import com.example.kilo.SongItemClean
import com.example.kilo.discovery.discoveryKey
import com.example.kilo.discovery.radio.RadioQueueBuilder
import com.example.kilo.discovery.smartshuffle.PlaylistAnalyzer

class ContinuationEngine(
    private val analyzer: PlaylistAnalyzer = PlaylistAnalyzer(),
    private val builder: RadioQueueBuilder = RadioQueueBuilder()
) {
    fun generate(
        playlist: List<SongItemClean>,
        catalog: List<SongItemClean>,
        playedRecently: Set<String>,
        excluded: Set<String> = emptySet(),
        count: Int = 10
    ): List<SongItemClean> {
        if (playlist.isEmpty()) return emptyList()
        val dna = analyzer.analyze(playlist, "continuation")
        val blocked = playlist.mapTo(mutableSetOf(), ::discoveryKey).apply { addAll(playedRecently); addAll(excluded) }
        return builder.rank(catalog, dna, blocked, count).filterNot { it.id in blocked || it.videoId in blocked }
    }
}
