package com.example.kilo.discovery.smartshuffle

import com.example.kilo.SongItemClean

class SmartShuffleRepository(private val feedback: DiscoveryFeedbackManager) {
    @Volatile private var catalogSnapshot: List<SongItemClean> = emptyList()
    fun updateCatalog(songs: List<SongItemClean>) { catalogSnapshot = songs.take(500).toList() }
    fun catalog(): List<SongItemClean> = catalogSnapshot
    fun allowedCandidates(): List<SongItemClean> {
        val excluded = feedback.neverRecommendIds() + feedback.removedIds()
        return catalogSnapshot.filterNot { it.id in excluded || it.videoId in excluded }
    }
}
