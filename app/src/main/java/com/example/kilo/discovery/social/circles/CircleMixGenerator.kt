package com.example.kilo.discovery.social.circles

import com.example.kilo.SongItemClean
import com.example.kilo.companion.CompanionProfile
import com.example.kilo.discovery.discoveryKey

class CircleMixGenerator(private val dna: CircleDNAEngine = CircleDNAEngine()) {
    fun generate(profiles: List<CompanionProfile>, catalog: List<SongItemClean>, limit: Int = 30): List<SongItemClean> {
        if (profiles.isEmpty()) return emptyList()
        val artists = dna.sharedArtists(profiles).map { it.lowercase() }
        val genres = dna.sharedGenres(profiles).map { it.lowercase() }
        return catalog.asSequence().filter { it.videoId.isNotBlank() || it.isDownloaded }.distinctBy(::discoveryKey)
            .sortedByDescending { song ->
                val artist = song.artist.lowercase().split(',', '&', '/').count { it.trim() in artists }
                val genre = genres.count { "${song.title} ${song.album} ${song.artist}".contains(it, true) }
                artist * 2 + genre
            }.take(limit.coerceIn(1, 50)).toList()
    }
}
