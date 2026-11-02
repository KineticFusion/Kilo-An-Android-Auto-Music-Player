package com.example.kilo.discovery.radio

import com.example.kilo.SongItemClean
import com.example.kilo.discovery.PlaylistDNA
import com.example.kilo.discovery.discoveryEnergy
import com.example.kilo.discovery.discoveryGenreAffinity
import com.example.kilo.discovery.discoveryKey
import com.example.kilo.discovery.normalizeDiscoveryText

class RadioQueueBuilder {
    fun next(seed: List<SongItemClean>, catalog: List<SongItemClean>, alreadyQueued: Set<String>, count: Int = 10): List<SongItemClean> {
        if (seed.isEmpty()) return emptyList()
        val dna = RadioDNAResolver().resolve(seed)
        return rank(catalog, dna, alreadyQueued, count)
    }

    fun rank(catalog: List<SongItemClean>, dna: PlaylistDNA, alreadyQueued: Set<String>, count: Int): List<SongItemClean> =
        catalog.asSequence().filter { it.videoId.isNotBlank() || it.isDownloaded }
            .filterNot { discoveryKey(it) in alreadyQueued || it.id in alreadyQueued || it.videoId in alreadyQueued }
            .distinctBy(::discoveryKey)
            .sortedByDescending { song ->
                val artist = song.artist.split(',', '&', '/').map { it.trim().lowercase() }
                    .maxOfOrNull { dna.artistWeights[it] ?: 0f } ?: 0f
                val tokens = normalizeDiscoveryText("${song.title} ${song.album}")
                val related = tokens.count { it in dna.titleTokens }.toFloat() / tokens.size.coerceAtLeast(1)
                val genre = discoveryGenreAffinity(song, dna.genreWeights)
                artist * 0.4f + related * 0.2f + genre * 0.15f +
                    (1f - kotlin.math.abs(discoveryEnergy(song) - dna.energy)) * 0.25f
            }.take(count.coerceIn(1, 20)).toList()
}
