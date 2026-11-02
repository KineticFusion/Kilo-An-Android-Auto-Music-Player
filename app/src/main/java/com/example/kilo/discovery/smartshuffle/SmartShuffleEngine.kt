package com.example.kilo.discovery.smartshuffle

import com.example.kilo.SongItemClean
import com.example.kilo.discovery.discoveryEnergy
import com.example.kilo.discovery.discoveryGenreAffinity
import com.example.kilo.discovery.discoveryKey
import com.example.kilo.discovery.normalizeDiscoveryText

class SmartShuffleEngine(
    private val analyzer: PlaylistAnalyzer = PlaylistAnalyzer(),
    private val injection: DiscoveryInjectionEngine = DiscoveryInjectionEngine()
) {
    fun build(
        playlist: List<SongItemClean>,
        candidates: List<SongItemClean>,
        excludedIds: Set<String> = emptySet(),
        playlistId: String = "active",
        preferredIds: Set<String> = emptySet()
    ): List<SongItemClean> {
        if (playlist.isEmpty()) return emptyList()
        val dna = analyzer.analyze(playlist, playlistId)
        val sourceIds = playlist.mapTo(mutableSetOf(), ::discoveryKey)
        val clean = candidates.asSequence()
            .filter { it.videoId.isNotBlank() || it.isDownloaded }
            .filterNot { discoveryKey(it) in sourceIds || it.id in excludedIds || it.videoId in excludedIds }
            .distinctBy(::discoveryKey)
            .sortedByDescending { candidate ->
                val artist = candidate.artist.split(',', '&', '/').map { it.trim().lowercase() }
                val artistAffinity = artist.maxOfOrNull { dna.artistWeights[it] ?: 0f } ?: 0f
                val candidateTokens = normalizeDiscoveryText("${candidate.title} ${candidate.album}")
                val tokenAffinity = candidateTokens.count { it in dna.titleTokens }.toFloat() / candidateTokens.size.coerceAtLeast(1)
                val genreAffinity = discoveryGenreAffinity(candidate, dna.genreWeights)
                val energyMatch = 1f - kotlin.math.abs(discoveryEnergy(candidate) - dna.energy)
                val preferred = if (discoveryKey(candidate) in preferredIds || candidate.id in preferredIds) 0.35f else 0f
                0.32f * artistAffinity + 0.19f * tokenAffinity + 0.16f * genreAffinity + 0.23f * energyMatch + preferred
            }.take(30).toList()
        return injection.inject(playlist, clean)
    }
}
