package com.example.kilo.discovery.smartshuffle

import com.example.kilo.SongItemClean
import com.example.kilo.discovery.PlaylistDNA
import com.example.kilo.discovery.discoveryEnergy
import com.example.kilo.discovery.discoveryKey
import com.example.kilo.discovery.normalizeDiscoveryText

class PlaylistAnalyzer {
    fun analyze(songs: List<SongItemClean>, playlistId: String = "active"): PlaylistDNA {
        val valid = songs.distinctBy(::discoveryKey)
        val artistCounts = valid.flatMap { it.artist.split(',', '&', '/').map { name -> name.trim() } }
            .filter(String::isNotBlank).groupingBy { it.lowercase() }.eachCount()
        val totalArtists = artistCounts.values.sum().coerceAtLeast(1)
        val artists = artistCounts.mapValues { (_, count) -> count.toFloat() / totalArtists }
        val tokens = valid.flatMap { normalizeDiscoveryText("${it.title} ${it.album}") }.toSet()
        val genreHints = mapOf(
            "electronic" to setOf("synthwave", "electronic", "edm", "dance", "techno", "house"),
            "hip-hop" to setOf("hip-hop", "rap", "trap", "phonk"),
            "rock" to setOf("rock", "metal", "punk", "alternative"),
            "pop" to setOf("pop", "dance", "chart"),
            "ambient" to setOf("ambient", "lofi", "piano", "acoustic", "focus")
        )
        val text = valid.joinToString(" ") { "${it.title} ${it.album} ${it.artist}" }.lowercase()
        val genres = genreHints.mapNotNull { (genre, hints) ->
            val hits = hints.count { hint -> text.contains(hint) }
            genre.takeIf { hits > 0 }?.let { it to hits.toFloat() }
        }.toMap()
        val totalGenreHits = genres.values.sum().coerceAtLeast(1f)
        return PlaylistDNA(
            playlistId = playlistId,
            artistWeights = artists,
            genreWeights = genres.mapValues { it.value / totalGenreHits },
            titleTokens = tokens,
            energy = valid.map(::discoveryEnergy).average().toFloat().takeIf { it.isFinite() } ?: 0.5f,
            discoveryAffinity = (0.25f + (1f - artists.values.maxOrNull().orZero()) * 0.75f).coerceIn(0f, 1f),
            sourceTrackCount = valid.size
        )
    }

    private fun Float?.orZero(): Float = this ?: 0f
}
