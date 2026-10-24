package com.example.kilo.discoveryradar

import com.example.kilo.companion.CompanionProfile
import com.example.kilo.companion.CompanionTrack
import com.example.kilo.companion.ListeningEvent
import java.util.Locale

/** Personalizes an existing recommendation result set without fetching or hiding its candidates. */
object MoodRecommendationRanker {
    fun rank(
        candidates: List<CompanionTrack>,
        mood: String,
        profile: CompanionProfile,
        events: List<ListeningEvent>,
        limit: Int = candidates.size
    ): List<CompanionTrack> {
        val moodTerms = moodTerms(mood)
        val artistWeights = mutableMapOf<String, Double>()
        events.forEach { event ->
            if (event.artist.isBlank()) return@forEach
            val weight = when (event.action) {
                "like" -> 9.0
                "complete" -> 5.0
                "listen" -> 2.0
                "replay" -> 4.0
                "skip", "unlike" -> -4.0
                else -> 0.0
            }
            val key = event.artist.normalized()
            artistWeights[key] = (artistWeights[key] ?: 0.0) + weight
        }
        val followed = profile.followedArtists.map { it.normalized() }.toSet()
        val favoriteGenres = profile.favoriteGenres.map { it.normalized() }.toSet()
        val scored = candidates.filter { it.id.isNotBlank() && it.id !in profile.excludedTrackIds }.distinctBy { it.id }.mapIndexed { index, track ->
            val artist = track.artist.normalized()
            val genres = track.genres.map { it.normalized() }.toSet()
            val moods = track.moods.map { it.normalized() }.toSet()
            val titleAndArtist = "${track.title} ${track.artist}".normalized()
            val explicitMoodMatch = moods.any { it in moodTerms } || moods.any { candidate -> moodTerms.any { term -> candidate.contains(term) } }
            val textMoodMatch = moodTerms.any { term -> titleAndArtist.contains(term) || genres.any { it.contains(term) } }
            val favoriteMoodMatch = moods.any { candidate -> profile.favoriteMoods.any { it.equals(candidate, true) } }
            val artistAffinity = (if (artist in followed) 18.0 else 0.0) +
                (artistWeights[artist] ?: 0.0).coerceIn(-12.0, 18.0) +
                (if (track.id in profile.likedTrackIds || track.isLiked) 12.0 else 0.0)
            val genreAffinity = genres.count { it in favoriteGenres } * 7.0
            val genreTextAffinity = favoriteGenres.count { it.length > 2 && titleAndArtist.contains(it) } * 3.0
            val languageAffinity = if (profile.favoriteLanguages.any { it.equals(track.language, true) && track.language.isNotBlank() }) 5.0 else 0.0
            val moodAffinity = (if (explicitMoodMatch) 12.0 else 0.0) + (if (textMoodMatch) 4.0 else 0.0) +
                (if (favoriteMoodMatch) 6.0 else 0.0)
            val freshAffinity = if (track.playCount == 0 && !track.isLiked) 2.0 else 0.0
            val discoveryAffinity = when (profile.discoveryLevel.lowercase(Locale.ROOT)) {
                "conservative" -> if (track.playCount > 0 || track.isLiked) 4.0 else -2.0
                "explorer" -> if (track.playCount == 0) 4.0 else 0.0
                "adventurer" -> if (track.playCount == 0) 7.0 else 0.0
                else -> freshAffinity
            }
            val score = artistAffinity + genreAffinity + genreTextAffinity + languageAffinity + moodAffinity + discoveryAffinity +
                track.trendDelta.coerceIn(-10f, 10f) * 0.35 + track.popularity.coerceIn(0, 100) * 0.03 - index * 0.0001
            track to score
        }
        val remaining = scored.sortedByDescending { it.second }.map { it.first }.toMutableList()
        val selected = mutableListOf<CompanionTrack>()
        val artistCounts = mutableMapOf<String, Int>()
        val target = limit.coerceAtLeast(0)
        while (remaining.isNotEmpty() && selected.size < target) {
            val index = remaining.indices.maxByOrNull { candidateIndex ->
                val candidate = remaining[candidateIndex]
                val originalScore = scored.firstOrNull { it.first.id == candidate.id }?.second ?: 0.0
                originalScore - (artistCounts[candidate.artist.normalized()] ?: 0) * 6.0
            } ?: break
            val track = remaining.removeAt(index)
            selected += track
            artistCounts[track.artist.normalized()] = (artistCounts[track.artist.normalized()] ?: 0) + 1
        }
        return selected
    }

    private fun moodTerms(mood: String): Set<String> {
        val base = mood.normalized().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 }.toMutableSet()
        val aliases = when {
            mood.contains("workout", true) || mood.contains("gym", true) -> setOf("workout", "gym", "running", "training", "energy", "phonk", "pump")
            mood.contains("night", true) || mood.contains("drive", true) -> setOf("night", "drive", "midnight", "neon", "city", "synthwave", "cruise")
            mood.contains("focus", true) || mood.contains("study", true) || mood.contains("coding", true) -> setOf("focus", "study", "lofi", "instrumental", "ambient", "coding", "work")
            mood.contains("relax", true) || mood.contains("rain", true) -> setOf("relax", "rain", "calm", "soft", "acoustic", "ambient", "chill")
            mood.contains("road trip", true) || mood.contains("commute", true) -> setOf("road", "trip", "commute", "journey", "drive", "travel")
            mood.contains("happy", true) || mood.contains("party", true) -> setOf("happy", "party", "dance", "celebration", "upbeat")
            mood.contains("romantic", true) -> setOf("love", "romantic", "heart", "slow", "soul")
            else -> emptySet()
        }
        base += aliases
        return base
    }

    private fun String.normalized() = lowercase(Locale.ROOT).trim()
}
