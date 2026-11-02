package com.example.kilo.discovery.mixes

import com.example.kilo.SongItemClean
import com.example.kilo.discovery.DiscoveryMix
import com.example.kilo.discovery.discoveryEnergy
import com.example.kilo.discovery.discoveryKey
import com.example.kilo.discovery.normalizeDiscoveryText
import com.example.kilo.companion.CompanionContext
import com.example.kilo.companion.ListeningEvent
import com.example.kilo.companion.CompanionProfile

class TimeAwareMixBuilder {
    data class MixProfile(val id: String, val title: String, val subtitle: String, val targetEnergy: Float, val cues: Set<String>, val discovery: Float)
    val profiles = listOf(
        MixProfile("morning_focus", "Morning Focus", "A clear start, shaped by what you like", 0.28f, setOf("focus", "study", "calm", "piano", "lofi"), 0.2f),
        MixProfile("afternoon_flow", "Afternoon Flow", "Keep your day moving", 0.55f, setOf("flow", "indie", "pop", "work", "dance"), 0.35f),
        MixProfile("evening_commute", "Evening Commute", "Familiar tracks with a few fresh turns", 0.55f, setOf("drive", "commute", "road", "night"), 0.25f),
        MixProfile("night_drive", "Night Drive", "A steady soundtrack for the late hours", 0.56f, setOf("night", "drive", "synthwave", "city", "indie"), 0.3f),
        MixProfile("midnight_discovery", "Midnight Discovery", "Explore sounds close to your taste", 0.48f, setOf("midnight", "ambient", "electronic", "dream"), 0.75f),
        MixProfile("weekend_mix", "Weekend Mix", "A little variety for time off", 0.65f, setOf("weekend", "party", "happy", "dance"), 0.55f),
        MixProfile("gym_power", "Gym Power", "High energy, built around your listening", 0.92f, setOf("gym", "workout", "power", "hype", "phonk"), 0.25f),
        MixProfile("study_session", "Study Session", "Low-distraction picks for focus", 0.22f, setOf("study", "focus", "instrumental", "lofi", "ambient"), 0.15f)
    )

    fun build(mix: MixProfile, catalog: List<SongItemClean>, events: List<ListeningEvent>, context: CompanionContext, userProfile: CompanionProfile = CompanionProfile(), limit: Int = 20): DiscoveryMix {
        val recentlyPlayed = events.asSequence().filter { context.nowMs - it.atMs < 7L * 86_400_000L }.map { it.trackId }.toSet()
        val frequentArtists = events.groupingBy { it.artist.lowercase() }.eachCount()
        val savedFromDiscovery = events.asSequence().filter { it.action == "discovery_keep" }.map { it.trackId }.toSet()
        val excludedIds = userProfile.excludedTrackIds
        val ranked = catalog.asSequence().filter { it.videoId.isNotBlank() || it.isDownloaded }
            .filterNot { discoveryKey(it) in excludedIds || it.id in excludedIds }
            .distinctBy(::discoveryKey)
            .sortedByDescending { song ->
                val text = "${song.title} ${song.artist} ${song.album}".lowercase()
                val cueScore = mix.cues.count { cue -> text.contains(cue) }.toFloat() / mix.cues.size.coerceAtLeast(1)
                val energy = 1f - kotlin.math.abs(discoveryEnergy(song) - mix.targetEnergy)
                val artist = frequentArtists[song.artist.lowercase()]?.coerceAtMost(8)?.div(8f) ?: 0f
                val followed = if (userProfile.followedArtists.any { song.artist.contains(it, true) }) 0.22f else 0f
                val genreMatch = userProfile.favoriteGenres.count { genre -> text.contains(genre, true) }.coerceAtMost(3) * 0.06f
                val moodMatch = userProfile.favoriteMoods.count { mood -> text.contains(mood, true) }.coerceAtMost(3) * 0.04f
                val languageMatch = if (userProfile.favoriteLanguages.any { song.languageCode.equals(it, true) }) 0.08f else 0f
                val disliked = if (discoveryKey(song) in userProfile.excludedTrackIds || song.id in userProfile.excludedTrackIds) -1f else 0f
                val liked = if (discoveryKey(song) in userProfile.likedTrackIds || song.id in userProfile.likedTrackIds ||
                    discoveryKey(song) in savedFromDiscovery || song.id in savedFromDiscovery) 0.30f else 0f
                val freshness = if (song.id in recentlyPlayed || song.videoId in recentlyPlayed) -0.35f else 0.18f
                val explicitPenalty = if (song.isExplicit) -0.05f else 0f
                cueScore * 0.34f + energy * 0.25f + artist * 0.12f + followed + genreMatch + moodMatch + languageMatch + freshness + liked + disliked + explicitPenalty +
                    normalizeDiscoveryText(song.title).size * mix.discovery * 0.001f
            }.take(limit.coerceIn(1, 40)).toList()
        return DiscoveryMix(mix.id, mix.title, mix.subtitle, ranked, context.nowMs)
    }
}
