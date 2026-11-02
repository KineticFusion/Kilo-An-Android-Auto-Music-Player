package com.example.kilo.discovery.social.circles

import com.example.kilo.companion.CompanionProfile

class CircleDNAEngine {
    fun sharedArtists(profiles: List<CompanionProfile>): List<String> =
        profiles.flatMap { it.followedArtists }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }
    fun sharedGenres(profiles: List<CompanionProfile>): List<String> =
        profiles.flatMap { it.favoriteGenres }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }
}
