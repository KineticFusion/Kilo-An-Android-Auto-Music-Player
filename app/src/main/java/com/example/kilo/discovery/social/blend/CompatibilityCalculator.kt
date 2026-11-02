package com.example.kilo.discovery.social.blend

import com.example.kilo.companion.CompanionProfile
import com.example.kilo.discovery.TasteBlendResult

class CompatibilityCalculator {
    fun calculate(first: CompanionProfile, second: CompanionProfile): TasteBlendResult {
        val sharedArtists = first.followedArtists.intersect(second.followedArtists)
        val sharedGenres = first.favoriteGenres.intersect(second.favoriteGenres)
        val union = (first.favoriteGenres + second.favoriteGenres).size.coerceAtLeast(1)
        val genreScore = sharedGenres.size.toFloat() / union
        val artistUnion = (first.followedArtists + second.followedArtists).size.coerceAtLeast(1)
        val artistScore = sharedArtists.size.toFloat() / artistUnion
        val discoveryBalance = if (first.discoveryLevel.equals(second.discoveryLevel, true)) 1f else 0.6f
        return TasteBlendResult(((genreScore * 0.55f + artistScore * 0.35f + discoveryBalance * 0.1f) * 100).toInt().coerceIn(0, 100), sharedArtists, sharedGenres, discoveryBalance)
    }
}
