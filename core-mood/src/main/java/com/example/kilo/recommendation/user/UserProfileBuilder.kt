package com.example.kilo.recommendation.user

import com.example.kilo.companion.CompanionProfile
import com.example.kilo.companion.ListeningEvent
import com.example.kilo.recommendation.genome.GenomeBuilder
import com.example.kilo.recommendation.genome.TrackGenome
import com.example.kilo.recommendation.intelligence.CompletionAnalyzer
import com.example.kilo.recommendation.intelligence.ListeningPatternEngine
import com.example.kilo.recommendation.intelligence.ReplayAnalyzer
import com.example.kilo.recommendation.intelligence.SkipAnalyzer
import kotlin.math.abs

object UserProfileBuilder {
    fun build(
        profile: CompanionProfile,
        events: List<ListeningEvent>,
        now: Long = System.currentTimeMillis(),
        genomesByAlias: Map<String, TrackGenome> = emptyMap()
    ): UserDNA {
        val artist = mutableMapOf<String, Float>()
        val genres = mutableMapOf<String, Float>()
        val languages = mutableMapOf<String, Float>()
        val moods = mutableMapOf<String, Float>()
        val factors = mutableMapOf<String, Float>()
        val liked = profile.likedTrackIds.toMutableSet()

        ListeningHistoryEngine.recent(events, now).forEach { event ->
            val days = ((now - event.atMs).coerceAtLeast(0) / 86_400_000L).toFloat()
            val recency = (1f - days / 180f).coerceIn(.05f, 1f)
            val duration = event.durationMs.coerceAtLeast(1L)
            val ratio = (event.listenedMs.toFloat() / duration).coerceIn(0f, 1f)
            val signal = when (event.action) {
                "skip" -> SkipAnalyzer.weight(event)
                "replay" -> ReplayAnalyzer.weight(event)
                "complete", "listen" -> CompletionAnalyzer.weight(event)
                "like" -> { liked += event.trackId; 2.8f }
                "unlike" -> -2.2f
                else -> if (ratio >= .8f) 1.2f else ratio
            } * recency

            if (event.artist.isNotBlank()) {
                val id = GenomeBuilder.identity(event.artist)
                artist[id] = ((artist[id] ?: 0f) + signal).coerceIn(-20f, 30f)
            }
            event.genres.forEach { genre ->
                val key = canonical(genre)
                if (key.isNotBlank()) genres[key] = ((genres[key] ?: 0f) + signal).coerceIn(-30f, 50f)
            }
            if (event.language.isNotBlank()) {
                val key = event.language.trim().lowercase()
                languages[key] = ((languages[key] ?: 0f) + signal).coerceIn(-20f, 40f)
            }
            if (event.mood.isNotBlank()) {
                val key = event.mood.trim().lowercase()
                moods[key] = ((moods[key] ?: 0f) + signal).coerceIn(-20f, 40f)
            }

            if (event.action == "skip" && event.listenedMs < 5_000L) {
                factors["sessionCompatibility"] = ((factors["sessionCompatibility"] ?: 50f) - 8f).coerceAtLeast(0f)
            }
            if (event.action == "complete") {
                factors["sessionCompatibility"] = ((factors["sessionCompatibility"] ?: 50f) + 2f).coerceAtMost(100f)
            }
            val eventGenome = genomesByAlias[event.trackId]
            if (eventGenome != null) {
                eventGenome.factors.filterKeys { eventGenome.evidence(it) > 0f }.forEach { (key, value) ->
                    val current = factors[key] ?: 50f
                    val learningRate = recency * (abs(signal).coerceAtMost(3f) / 3f) * .10f
                    val direction = if (signal < 0f) -1f else 1f
                    factors[key] = (current + (value - current) * learningRate * direction).coerceIn(0f, 100f)
                }
            }
        }

        profile.favoriteGenres.forEach { genres.putIfAbsent(canonical(it), 5f) }
        profile.favoriteLanguages.forEach { languages.putIfAbsent(it.trim().lowercase(), 5f) }
        profile.favoriteMoods.forEach { moods.putIfAbsent(it.trim().lowercase(), 5f) }
        profile.followedArtists.forEach { artist.putIfAbsent(GenomeBuilder.identity(it), 7f) }

        val aliasMap = genomesByAlias
        val positiveSeedIds = buildSet {
            addAll(profile.likedTrackIds)
            events.asSequence().filter { it.action in setOf("like", "replay", "complete") }
                .mapTo(this) { it.trackId }
        }
        val pool = aliasMap.values.distinctBy { it.trackId }
        val seeds = positiveSeedIds.asSequence().take(8).mapNotNull(aliasMap::get).distinctBy { it.trackId }.toList()
        val clusterIds = UserTasteGraph().cluster(seeds, pool, 40).mapTo(hashSetOf()) { it.trackId }

        return UserDNA(
            artistAffinity = artist,
            genreAffinity = genres,
            languageAffinity = languages,
            moodAffinity = moods,
            factorAffinity = factors,
            likedTrackIds = liked,
            excludedTrackIds = profile.excludedTrackIds,
            discoveryPreference = ListeningPatternEngine.discoveryPreference(profile.discoveryLevel),
            preferredLanguageShare = 85f,
            tasteClusterTrackIds = clusterIds
        )
    }

    private fun canonical(value: String) = value.trim().lowercase().replace('_', ' ').replace('-', ' ')
}
