package com.example.kilo.discoveryradar

import com.example.kilo.companion.CompanionProfile
import com.example.kilo.companion.CompanionTrack
import com.example.kilo.companion.ListeningEvent
import java.util.Locale

data class RadarSection(val id: String, val title: String, val description: String, val tracks: List<CompanionTrack>)

/** Ranks a caller-provided cached catalog. It does not call search, providers, or playback. */
object DiscoveryRadar {
    fun build(catalog: List<CompanionTrack>, events: List<ListeningEvent>, profile: CompanionProfile,
              nowMs: Long = System.currentTimeMillis(), perSection: Int = 8): List<RadarSection> {
        val safe = catalog.filter { it.id.isNotBlank() && it.id !in profile.excludedTrackIds }.distinctBy { it.id }
        if (safe.isEmpty()) return emptyList()
        val lastByTrack = events.groupBy { it.trackId }.mapValues { (_, values) -> values.maxOfOrNull { it.atMs } ?: 0L }
        fun matchesText(track: CompanionTrack, terms: Set<String>): Boolean {
            val text = "${track.title} ${track.artist} ${track.album}".lowercase(Locale.ROOT)
            return terms.any { it.length >= 3 && text.contains(it.lowercase(Locale.ROOT)) }
        }
        fun section(id: String, title: String, description: String, songs: List<CompanionTrack>) =
            songs.distinctBy { it.id }.take(perSection.coerceIn(1, 20)).takeIf { it.isNotEmpty() }
                ?.let { RadarSection(id, title, description, it) }
        val candidates = listOfNotNull(
            section("rising", "Rising artists", "Fresh momentum from your cached catalog.", safe.sortedByDescending { it.trendDelta }),
            section("hidden_gems", "Hidden gems", "Less-played tracks with room to surprise.", safe.filter { it.popularity < 45 && it.playCount < 3 }.sortedByDescending { it.trendDelta }),
            section("similar_artists", "Similar artists", "Built from artists in your listening profile.", safe.filter { t ->
                profile.followedArtists.any { it.equals(t.artist, true) } || events.any { it.artist.equals(t.artist, true) && it.action in setOf("like", "complete") }
            }.sortedByDescending { it.popularity }),
            section("forgotten", "Forgotten favorites", "Liked tracks you have not revisited recently.", safe.filter { t ->
                (t.isLiked || t.id in profile.likedTrackIds) && (lastByTrack[t.id] ?: t.lastPlayedAtMs) < nowMs - 30L * DAY
            }.sortedByDescending { lastByTrack[it.id] ?: it.lastPlayedAtMs }),
            section("genre_trending", "Trending in your genres", "Popular picks aligned with saved genres.", safe.filter { t ->
                t.genres.any { g -> profile.favoriteGenres.any { it.equals(g, true) } } || matchesText(t, profile.favoriteGenres)
            }.sortedByDescending { it.popularity + it.trendDelta.toInt() }),
            section("language_trending", "Trending in your languages", "Matches languages in your profile.", safe.filter { t -> profile.favoriteLanguages.any { it.equals(t.language, true) } }.sortedByDescending { it.popularity }),
            section("mood_trending", "For your moods", "Cached tracks that fit the moods you enjoy.", safe.filter { t ->
                t.moods.any { m -> profile.favoriteMoods.any { it.equals(m, true) } } || matchesText(t, profile.favoriteMoods)
            }.sortedByDescending { it.popularity }),
            section("similar_songs", "Songs like your favorites", "A discovery mix shaped by familiar artists and genres.", safe.filter { t ->
                t.id !in profile.likedTrackIds && (profile.followedArtists.any { it.equals(t.artist, true) } ||
                    t.genres.any { g -> profile.favoriteGenres.any { it.equals(g, true) } } || matchesText(t, profile.favoriteGenres))
            }.sortedByDescending { it.trendDelta * 2 + it.popularity }),
            section("albums", "Album discovery", "Albums represented in your available catalog.", safe.filter { it.album.isNotBlank() && it.album.lowercase(Locale.ROOT) != "unknown" }.sortedByDescending { it.popularity })
        )
        val seen = mutableSetOf<String>()
        return candidates.mapNotNull { shelf -> shelf.copy(tracks = shelf.tracks.filter { seen.add(it.id) }).takeIf { it.tracks.isNotEmpty() } }
    }

    private const val DAY = 24L * 60 * 60 * 1000
}
