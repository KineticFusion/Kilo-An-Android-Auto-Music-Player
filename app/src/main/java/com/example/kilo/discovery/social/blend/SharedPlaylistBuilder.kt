package com.example.kilo.discovery.social.blend

import com.example.kilo.SongItemClean
import com.example.kilo.companion.CompanionProfile
import com.example.kilo.discovery.discoveryKey

class SharedPlaylistBuilder {
    fun build(first: CompanionProfile, second: CompanionProfile, catalog: List<SongItemClean>, limit: Int = 30): List<SongItemClean> {
        val sharedArtists = first.followedArtists.intersect(second.followedArtists).map { it.lowercase() }.toSet()
        val sharedGenres = first.favoriteGenres.intersect(second.favoriteGenres).map { it.lowercase() }.toSet()
        val liked = first.likedTrackIds.intersect(second.likedTrackIds)
        return catalog.asSequence().filter { it.videoId.isNotBlank() || it.isDownloaded }.distinctBy(::discoveryKey)
            .sortedByDescending { song ->
                val artist = song.artist.lowercase().split(',', '&', '/').any { it.trim() in sharedArtists }
                val text = "${song.title} ${song.album} ${song.artist}".lowercase()
                (if (discoveryKey(song) in liked || song.id in liked) 4 else 0) +
                    (if (artist) 3 else 0) + sharedGenres.count { genre -> text.contains(genre) }
            }.take(limit.coerceIn(1, 50)).toList()
    }
}
