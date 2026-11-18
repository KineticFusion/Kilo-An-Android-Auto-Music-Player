package com.example.kilo.spotify.matching

import com.example.kilo.spotify.SpotifyTrack
import com.example.kilo.SongItemClean
import kotlin.math.abs

object TrackMatcher {

    data class MatchResult(
        val score: Double,
        val matchType: MatchType,
        val matchedTrack: SpotifyTrack? = null
    ) {
        val isStrongMatch: Boolean = score >= 0.85
        val isPossibleMatch: Boolean = score >= 0.65
    }

    enum class MatchType {
        ISRC,
        EXACT,
        FUZZY_TITLE_ARTIST,
        FUZZY_TITLE_ONLY,
        DURATION_BASED
    }

    private const val ISRC_WEIGHT = 1.0
    private const val EXACT_TITLE_ARTIST_WEIGHT = 0.95
    private const val FUZZY_TITLE_ARTIST_WEIGHT = 0.75
    private const val FUZZY_TITLE_ONLY_WEIGHT = 0.55
    private const val DURATION_TOLERANCE_MS = 3000

    fun findBestMatch(spotifyTrack: SpotifyTrack, candidates: List<SongItemClean>): MatchResult? {
        if (candidates.isEmpty()) return null

        var bestMatch: MatchResult? = null

        for (candidate in candidates) {
            val result = matchTrack(spotifyTrack, candidate)
            if (result != null && (bestMatch == null || result.score > bestMatch.score)) {
                bestMatch = result
            }
        }

        return bestMatch?.takeIf { it.isPossibleMatch }
    }

    fun matchTrack(spotifyTrack: SpotifyTrack, candidate: SongItemClean): MatchResult? {
        // Try ISRC match if available (would need ISRC in both models)
        // For now, skip ISRC as not available in current models

        // Exact title + artist match
        val exactScore = calculateExactMatchScore(spotifyTrack, candidate)
        if (exactScore >= EXACT_TITLE_ARTIST_WEIGHT) {
            return MatchResult(exactScore, MatchType.EXACT, spotifyTrack)
        }

        // Fuzzy title + artist match
        val fuzzyScore = calculateFuzzyMatchScore(spotifyTrack, candidate)
        if (fuzzyScore >= FUZZY_TITLE_ARTIST_WEIGHT) {
            return MatchResult(fuzzyScore, MatchType.FUZZY_TITLE_ARTIST, spotifyTrack)
        }

        // Duration-based match as fallback
        val durationScore = calculateDurationMatchScore(spotifyTrack, candidate)
        if (durationScore >= FUZZY_TITLE_ONLY_WEIGHT) {
            return MatchResult(durationScore, MatchType.DURATION_BASED, spotifyTrack)
        }

        return null
    }

    private fun calculateExactMatchScore(track: SpotifyTrack, candidate: SongItemClean): Double {
        val titleMatch = normalize(track.name) == normalize(candidate.title)
        val artistMatch = track.artists.any { normalize(it.name) == normalize(candidate.artist) }

        return when {
            titleMatch && artistMatch -> EXACT_TITLE_ARTIST_WEIGHT
            titleMatch -> 0.7
            artistMatch -> 0.5
            else -> 0.0
        }
    }

    private fun calculateFuzzyMatchScore(track: SpotifyTrack, candidate: SongItemClean): Double {
        val titleSimilarity = stringSimilarity(normalize(track.name), normalize(candidate.title))
        val artistSimilarity = track.artists.map { stringSimilarity(normalize(it.name), normalize(candidate.artist)) }.maxOrNull() ?: 0.0

        // Weighted combination: 60% title, 40% artist
        return (titleSimilarity * 0.6 + artistSimilarity * 0.4) * FUZZY_TITLE_ARTIST_WEIGHT
    }

    private fun calculateDurationMatchScore(track: SpotifyTrack, candidate: SongItemClean): Double {
        val durationDiff = abs(track.durationMs - candidate.durationMs)
        if (durationDiff <= DURATION_TOLERANCE_MS) {
            val titleSimilarity = stringSimilarity(normalize(track.name), normalize(candidate.title))
            return titleSimilarity * FUZZY_TITLE_ONLY_WEIGHT
        }
        return 0.0
    }

    private fun normalize(str: String): String {
        return str.lowercase()
            .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun stringSimilarity(s1: String, s2: String): Double {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        if (s1 == s2) return 1.0

        // Jaro-Winkler similarity for better fuzzy matching
        return jaroWinklerSimilarity(s1, s2)
    }

    private fun jaroWinklerSimilarity(s1: String, s2: String): Double {
        val jaro = jaroSimilarity(s1, s2)
        if (jaro < 0.7) return jaro

        // Calculate common prefix (up to 4 characters)
        var prefixLength = 0
        for (i in 0 until minOf(4, s1.length, s2.length)) {
            if (s1[i] == s2[i]) prefixLength++ else break
        }

        // Winkler adjustment
        return jaro + 0.1 * prefixLength * (1.0 - jaro)
    }

    private fun jaroSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0

        val len1 = s1.length
        val len2 = s2.length

        if (len1 == 0 || len2 == 0) return 0.0

        val matchDistance = maxOf(len1, len2) / 2 - 1
        val s1Matches = BooleanArray(len1)
        val s2Matches = BooleanArray(len2)

        var matches = 0
        var transpositions = 0

        for (i in 0 until len1) {
            val start = maxOf(0, i - matchDistance)
            val end = minOf(i + matchDistance + 1, len2)

            for (j in start until end) {
                if (s2Matches[j]) continue
                if (s1[i] != s2[j]) continue
                s1Matches[i] = true
                s2Matches[j] = true
                matches++
                break
            }
        }

        if (matches == 0) return 0.0

        var k = 0
        for (i in 0 until len1) {
            if (!s1Matches[i]) continue
            while (!s2Matches[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }

        val transpositionsHalf = transpositions / 2.0
        return (matches.toDouble() / len1 + matches.toDouble() / len2 + (matches - transpositionsHalf) / matches) / 3.0
    }

    fun findCrossServiceMatches(spotifyTracks: List<SpotifyTrack>, youtubeTracks: List<SongItemClean>): Map<SpotifyTrack, SongItemClean> {
        val matches = mutableMapOf<SpotifyTrack, SongItemClean>()
        val usedYoutubeTracks = mutableSetOf<SongItemClean>()

        for (spotifyTrack in spotifyTracks) {
            val availableCandidates = youtubeTracks.filterNot { it in usedYoutubeTracks }
            val bestMatch = findBestMatch(spotifyTrack, availableCandidates)

            if (bestMatch != null && bestMatch.isStrongMatch && bestMatch.matchedTrack != null) {
                // Find the corresponding youtube track
                val matchedYoutubeTrack = availableCandidates.firstOrNull { candidate ->
                    matchTrack(bestMatch.matchedTrack!!, candidate)?.score == bestMatch.score
                }

                matchedYoutubeTrack?.let {
                    matches[spotifyTrack] = it
                    usedYoutubeTracks.add(it)
                }
            }
        }

        return matches
    }
}