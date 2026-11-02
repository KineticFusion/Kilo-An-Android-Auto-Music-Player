package com.example.kilo.discovery.smartshuffle

import com.example.kilo.SongItemClean
import com.example.kilo.discovery.discoveryKey

/** Interleaves one new track after each block of three, keeping every source item in order. */
class DiscoveryInjectionEngine {
    fun inject(
        playlist: List<SongItemClean>,
        discoveries: List<SongItemClean>,
        tracksBetweenDiscoveries: Int = 3
    ): List<SongItemClean> {
        if (playlist.isEmpty() || discoveries.isEmpty()) return playlist
        val every = tracksBetweenDiscoveries.coerceAtLeast(1)
        val originalIds = playlist.mapTo(mutableSetOf(), ::discoveryKey)
        val candidates = discoveries.distinctBy(::discoveryKey).filterNot { discoveryKey(it) in originalIds }
        if (candidates.isEmpty()) return playlist
        val output = ArrayList<SongItemClean>(playlist.size + candidates.size)
        var discoveryIndex = 0
        playlist.forEachIndexed { index, song ->
            output += song
            val blockEnded = (index + 1) % every == 0
            if (blockEnded && discoveryIndex < candidates.size && index < playlist.lastIndex) {
                output += candidates[discoveryIndex++]
            }
        }
        return output
    }
}
