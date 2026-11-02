package com.example.kilo

import java.util.Locale
import kotlin.math.max

/** Applies the v5 feed mix and exposure cooldown after the existing Genome X ranking. */
internal object FeedFreshnessEngine {
    private enum class Lane { FAMILIAR, EXPLORATION, WILDCARD }

    fun compose(
        ranked: List<SongItemClean>,
        likedTrackIds: Set<String>,
        playedRecentlyIds: Set<String>,
        shownAtMs: Map<String, Long>,
        engagedArtistKeys: Set<String>,
        familiarArtistKeys: Set<String>,
        familiarGenreKeys: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
        limit: Int = ranked.size
    ): List<SongItemClean> {
        if (ranked.isEmpty() || limit <= 0) return emptyList()
        val unique = ranked.distinctBy { it.videoId.ifBlank { it.id } }
        fun aliases(song: SongItemClean) = setOf(song.id, song.videoId).filter(String::isNotBlank)
        fun artistKey(song: SongItemClean) = key(song.artist)
        fun isEngaged(song: SongItemClean) = aliases(song).any { it in likedTrackIds } || artistKey(song) in engagedArtistKeys
        fun exposure(song: SongItemClean) = maxOf(shownAtMs[song.id] ?: 0L, shownAtMs[song.videoId] ?: 0L)
        fun lane(song: SongItemClean): Lane {
            if (isEngaged(song) || artistKey(song) in familiarArtistKeys) return Lane.FAMILIAR
            val genreEvidence = familiarGenreKeys.any { genre ->
                genre.isNotBlank() && (song.title.contains(genre, true) || song.album.contains(genre, true) || song.artist.contains(genre, true))
            }
            if (genreEvidence || (exposure(song) == 0L && aliases(song).none { it in playedRecentlyIds })) return Lane.EXPLORATION
            return Lane.WILDCARD
        }

        // The ranked list is the primary signal; freshness only moves repeatedly surfaced tracks down.
        val positions = unique.mapIndexed { index, song -> song.videoId.ifBlank { song.id } to index }.toMap()
        fun freshnessAdjusted(pool: List<SongItemClean>): List<SongItemClean> = pool.filter { song ->
            val seen = exposure(song)
            seen == 0L || nowMs - seen >= 14L * DAY_MS || isEngaged(song)
        }.sortedWith(
            compareBy<SongItemClean> { song ->
                val seen = exposure(song)
                if (seen == 0L) 0L else {
                    val ageDays = ((nowMs - seen).coerceAtLeast(0L) / DAY_MS)
                    when {
                        isEngaged(song) -> 0L
                        ageDays < 14L -> 10_000L + (14L - ageDays) * 400L
                        else -> (max(0L, 45L - ageDays) * 35L)
                    }
                }
            }.thenBy { song ->
                val aliases = aliases(song)
                if (aliases.any { it in playedRecentlyIds } && !isEngaged(song)) 1 else 0
            }.thenBy { positions[it.videoId.ifBlank { it.id }] ?: Int.MAX_VALUE }
        )

        val familiar = freshnessAdjusted(unique.filter { lane(it) == Lane.FAMILIAR })
        val exploration = freshnessAdjusted(unique.filter { lane(it) == Lane.EXPLORATION })
        val wildcards = freshnessAdjusted(unique.filter { lane(it) == Lane.WILDCARD })
        val result = ArrayList<SongItemClean>(minOf(limit, unique.size))
        val used = HashSet<String>()
        fun takeFrom(pool: List<SongItemClean>, count: Int) {
            pool.asSequence().filter { used.add(it.videoId.ifBlank { it.id }) }.take(count).forEach(result::add)
        }

        val count = minOf(limit, unique.size)
        val familiarTarget = (count * 70 + 99) / 100
        val explorationTarget = (count * 20 + 99) / 100
        val wildcardTarget = count - familiarTarget - explorationTarget
        takeFrom(familiar, familiarTarget)
        takeFrom(exploration, explorationTarget)
        takeFrom(wildcards, wildcardTarget)
        // A sparse source pool must still fill the feed; use the next best fresh candidates.
        takeFrom(familiar + exploration + wildcards, count - result.size)
        takeFrom(unique.filter { song ->
            val seen = exposure(song)
            seen == 0L || nowMs - seen >= 14L * DAY_MS || isEngaged(song)
        }, count - result.size)
        // Tiny catalogs should never leave Home empty; only exhausted pools can use a cooled item.
        takeFrom(unique, count - result.size)
        return result
    }

    private fun key(value: String): String = value.trim().lowercase(Locale.ROOT)
    private const val DAY_MS = 86_400_000L
}
