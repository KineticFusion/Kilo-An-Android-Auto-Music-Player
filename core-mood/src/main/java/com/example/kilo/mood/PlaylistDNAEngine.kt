package com.example.kilo.mood

import java.util.Locale
import kotlin.math.sqrt

/** Builds a compact taste signature from a playlist's already-extracted track features. */
object PlaylistDNAEngine {
    data class Profile(
        val genres: Map<String, Float>,
        val languages: Map<String, Float>,
        val attributes: Map<String, Float>,
        val artists: Map<String, Float>
    ) {
        fun searchQueries(limit: Int = 6): List<String> {
            val topGenres = genres.entries.sortedByDescending { it.value }.take(3).map { it.key }
            val topArtists = artists.entries.sortedByDescending { it.value }.take(2).map { it.key }
            val topLanguages = languages.entries.sortedByDescending { it.value }.take(2).map { it.key }
            return buildList {
                topGenres.forEach { add("new $it songs") }
                topArtists.forEach { add("new songs like $it") }
                if (topGenres.isNotEmpty() && topLanguages.isNotEmpty()) {
                    add("${topLanguages.first()} ${topGenres.first()} songs")
                }
                topGenres.firstOrNull()?.let { add("hidden gems in $it music") }
            }.map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase(Locale.ROOT) }.take(limit.coerceIn(1, 12))
        }
    }

    fun analyze(tracks: Collection<TrackFeatureProfile>): Profile {
        if (tracks.isEmpty()) return Profile(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        val weights = tracks.map { it.metadataConfidence.coerceIn(0.2f, 1f) }
        fun aggregate(selector: (TrackFeatureProfile) -> Map<String, Float>): Map<String, Float> {
            val sums = linkedMapOf<String, Float>()
            val totals = linkedMapOf<String, Float>()
            tracks.forEachIndexed { index, track ->
                val confidence = weights[index]
                selector(track).forEach { (rawKey, rawValue) ->
                    val key = rawKey.trim().lowercase(Locale.ROOT)
                    if (key.isBlank()) return@forEach
                    val value = rawValue.coerceIn(0f, 1f) * confidence
                    sums[key] = (sums[key] ?: 0f) + value
                    totals[key] = (totals[key] ?: 0f) + confidence
                }
            }
            return sums.mapValues { (key, sum) -> (sum / (totals[key] ?: 1f)).coerceIn(0f, 1f) }
        }
        val artists = tracks.groupingBy { it.artistKey.trim().lowercase(Locale.ROOT) }
            .eachCount().filterKeys(String::isNotBlank)
            .mapValues { (_, count) -> count.toFloat() / tracks.size }
        return Profile(
            genres = aggregate { it.genres },
            languages = aggregate { it.languages },
            attributes = aggregate { it.attributes },
            artists = artists
        )
    }

    /** Scores how closely one track's stable feature profile fits the playlist signature. */
    fun score(profile: Profile, track: TrackFeatureProfile): Float {
        val genre = cosine(profile.genres, track.genres)
        val language = cosine(profile.languages, track.languages)
        val attributes = cosine(profile.attributes, track.attributes)
        val artist = profile.artists[track.artistKey.trim().lowercase(Locale.ROOT)] ?: 0f
        val weighted = listOf(
            (genre to 0.58f),
            (attributes to 0.27f),
            (language to 0.10f),
            (artist to 0.05f)
        ).filter { (value, _) -> value > 0f }
        if (weighted.isEmpty()) return 0f
        var weightedTotal = 0f
        var weightTotal = 0f
        weighted.forEach { (value, weight) ->
            weightedTotal += value * weight
            weightTotal += weight
        }
        return if (weightTotal > 0f) weightedTotal / weightTotal else 0f
    }

    private fun cosine(left: Map<String, Float>, right: Map<String, Float>): Float {
        if (left.isEmpty() || right.isEmpty()) return 0f
        val keys = left.keys + right.keys
        var dot = 0.0
        var leftMagnitudeSquared = 0.0
        var rightMagnitudeSquared = 0.0
        keys.forEach { key ->
            val leftValue = (left[key] ?: 0f).toDouble()
            val rightValue = (right[key] ?: 0f).toDouble()
            dot += leftValue * rightValue
            leftMagnitudeSquared += leftValue * leftValue
            rightMagnitudeSquared += rightValue * rightValue
        }
        val leftNorm = sqrt(leftMagnitudeSquared)
        val rightNorm = sqrt(rightMagnitudeSquared)
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0f
        return (dot / (leftNorm * rightNorm)).toFloat().coerceIn(0f, 1f)
    }
}
