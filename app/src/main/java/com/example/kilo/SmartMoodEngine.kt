package com.example.kilo

import java.util.Locale
import com.example.kilo.audioanalysis.TrackAnalysisEntity

data class SmartMoodProfile(
    val name: String, val genres: List<String>, val energy: Int, val tempo: Int,
    val discovery: Int, val familiarity: Int, val focus: Int, val emotion: Int,
    val replayBias: Int, val crossfadeDurationMs: Long, val equalizerProfile: String,
    val themePreset: String, val themeColorArgb: Int, val androidAutoProfile: String
)

/** Recommendation and presentation hints wrapped around KILO's existing player. */
object SmartMoodEngine {
    private fun p(name: String, genres: String, energy: Int, tempo: Int, discovery: Int,
                  familiarity: Int, focus: Int, emotion: Int, replay: Int, fade: Long,
                  eq: String, theme: String, color: Long, auto: String) = SmartMoodProfile(
        name, genres.split('|'), energy, tempo, discovery, familiarity, focus, emotion,
        replay, fade, eq, theme, color.toInt(), auto
    )
    private val profiles = listOf(
        p("Night Drive", "Synthwave|Electronic|Alternative|Indie|Chill EDM", 60, 50, 30, 80, 50, 90, 85, 5000, "Bass Lover", "Neon Dark", 0xFF58B8FF, "Low-distraction driving mix"),
        p("Workout", "Hip-Hop|Trap|Phonk|EDM", 100, 95, 50, 70, 10, 85, 50, 1200, "Bass Lover", "Energy Red", 0xFFFF5364, "High-energy quick controls"),
        p("Focus", "Lofi|Instrumental|Jazz|Ambient", 25, 20, 20, 90, 100, 35, 85, 3000, "Balanced", "Minimal", 0xFF9BA7B4, "Low-distraction focus mix"),
        p("Road Trip", "Pop|Rock|Indie|Hip-Hop|Country", 70, 65, 85, 65, 35, 75, 40, 5000, "Audiophile", "Open Road", 0xFFFFB45B, "Long-form driving mix"),
        p("Commute", "Pop|Alternative|Electronic", 55, 60, 40, 80, 55, 65, 65, 2500, "Vocal Focus", "Daylight", 0xFF5DD6C0, "Quick commute mix"),
        p("Relax", "Ambient|Acoustic|Lofi|Jazz", 30, 30, 30, 80, 70, 75, 70, 4000, "Balanced", "Soft Glow", 0xFF9C8BFF, "Calm listening mix"),
        p("Rain Drive", "Alternative|Indie|Ambient|Electronic", 45, 45, 35, 80, 55, 75, 70, 4500, "Balanced", "Rainy Night", 0xFF6797C7, "Low-distraction rainy drive mix"),
        p("Coffee Vibes", "Lofi|Jazz|Acoustic|Indie", 40, 48, 30, 85, 75, 60, 75, 3200, "Vocal Focus", "Warm Coffee", 0xFFD2A36C, "Easy daytime listening mix"),
        p("Study", "Lofi|Piano|Classical|Instrumental", 20, 20, 20, 85, 100, 25, 85, 3000, "Balanced", "Minimal", 0xFF9BA7B4, "Low-distraction focus mix"),
        p("Coding", "Electronic|Lofi|Ambient|Instrumental", 35, 45, 35, 75, 90, 40, 70, 3000, "Balanced", "Minimal", 0xFF63C8E8, "Low-distraction focus mix"),
        p("Gaming", "Electronic|Phonk|Hip-Hop|Soundtrack", 75, 75, 60, 55, 45, 70, 35, 1500, "Bass Lover", "Arcade", 0xFF9B72FF, "Quick controls"),
        p("Sleep", "Ambient|Piano|Classical|Nature Sounds", 10, 10, 10, 80, 95, 30, 90, 4000, "Balanced", "Midnight", 0xFF6676C8, "Low-distraction night mix"),
        p("Party", "Dance|EDM|Pop|Hip-Hop", 90, 90, 60, 65, 10, 90, 40, 1000, "Bass Lover", "Vivid", 0xFFFF5EA8, "High-energy quick controls"),
        p("High Energy", "EDM|Pop|Rock|Hip-Hop", 90, 85, 55, 60, 20, 75, 45, 1200, "Bass Lover", "Energy Red", 0xFFFF6848, "High-energy quick controls"),
        p("Happy", "Pop|Funk|Disco|Dance", 75, 75, 50, 75, 20, 95, 60, 2000, "Vocal Focus", "Sunrise", 0xFFFFC857, "Upbeat quick mix"),
        p("Sad", "Acoustic|Indie|Soul|Alternative", 25, 35, 25, 75, 35, 95, 75, 4000, "Vocal Focus", "Blue Hour", 0xFF778FE8, "Calm listening mix"),
        p("Romantic", "R&B|Soul|Pop|Jazz", 35, 45, 30, 80, 30, 95, 70, 3500, "Vocal Focus", "Rose", 0xFFFF7898, "Calm listening mix"),
        p("Nostalgic", "Classic Pop|80s|90s|Rock", 55, 60, 20, 95, 25, 90, 90, 3000, "Audiophile", "Retro", 0xFFDAA567, "Familiar favorites mix"),
        p("Devotional", "Devotional|Classical|Ambient|Instrumental", 35, 30, 25, 85, 70, 80, 75, 3500, "Balanced", "Dawn", 0xFFFFC875, "Calm listening mix"),
        p("Spiritual Morning", "Devotional|Ambient|Classical|Acoustic", 40, 35, 35, 85, 65, 85, 70, 3500, "Balanced", "Dawn", 0xFFFFC875, "Calm listening mix")
    ).associateBy { it.name.lowercase(Locale.ROOT) }

    private val moodAliases = linkedMapOf(
        "Hype Mode" to "High Energy",
        "Chill" to "Relax",
        "Gym" to "Workout",
        "Rainy Day" to "Rain Drive",
        "Heartbreak" to "Sad"
    )

    fun profile(name: String): SmartMoodProfile? {
        val key = name.trim().lowercase(Locale.ROOT)
        return profiles[key] ?: moodAliases.entries.firstOrNull { it.key.lowercase(Locale.ROOT) == key }
            ?.let { profiles[it.value.lowercase(Locale.ROOT)] }
    }

    /** Complete user-facing catalogue, including familiar names backed by existing mood profiles. */
    fun allMoodNames(): List<String> = buildList {
        addAll(moodAliases.keys)
        addAll(profiles.values.map { it.name })
    }.distinctBy { it.lowercase(Locale.ROOT) }

    fun queryModifiers(name: String): List<String> {
        val mood = profile(name) ?: return emptyList()
        val bias = if (mood.discovery >= 70) "new discoveries" else if (mood.familiarity >= 85) "popular favorites" else "music mix"
        return (mood.genres.take(3) + bias).distinct()
    }

    /** Retains provider ranking while biasing the queue toward familiar or undiscovered tracks. */
    fun rankSongs(
        songs: List<SongItemClean>,
        mood: String,
        playedIds: Set<String> = emptySet(),
        likedIds: Set<String> = emptySet(),
        preferredArtists: Set<String> = emptySet(),
        audioAnalysis: Map<String, TrackAnalysisEntity> = emptyMap()
    ): List<SongItemClean> {
        val profile = profile(mood) ?: return songs
        val artistPreferences = preferredArtists.map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank)
        return songs.withIndex().sortedByDescending { row ->
            val song = row.value
            val keys = listOf(song.id, song.videoId)
            val played = keys.any { it.isNotBlank() && it in playedIds }
            val liked = keys.any { it.isNotBlank() && it in likedIds }
            val words = "${song.title} ${song.artist} ${song.album}".lowercase()
            val genreMatches = profile.genres.count { words.contains(it.lowercase()) }
            val artistMatches = artistPreferences.count { song.artist.lowercase(Locale.ROOT).contains(it) }
            val analysis = audioAnalysis[song.id] ?: audioAnalysis[song.videoId]
            val audioFit = analysis?.let { measured ->
                val energyFit = 1.0 - kotlin.math.abs(measured.energy - profile.energy) / 100.0
                val targetBpm = 55.0 + profile.tempo * 1.45
                val tempoFit = measured.bpm?.let { 1.0 - (kotlin.math.abs(it - targetBpm) / 145.0).coerceIn(0.0, 1.0) } ?: 0.0
                energyFit.coerceIn(0.0, 1.0) * 0.24 + tempoFit.coerceIn(0.0, 1.0) * 0.16
            } ?: 0.0
            (if (played) profile.familiarity * 0.008 + profile.replayBias * 0.004 else profile.discovery * 0.012) +
                (if (liked) profile.familiarity * 0.008 else 0.0) + genreMatches * 0.18 + artistMatches * 2.5 + audioFit - row.index * 0.0001
        }.map { it.value }
    }

    /** Keeps the established suggestion behavior for existing callers, including Android Auto. */
    fun suggestForConditions(hourOfDay: Int, temperatureC: Double?): MoodSuggestion {
        val hour = hourOfDay.coerceIn(0, 23)
        val mood = when {
            hour >= 21 || hour < 5 -> "Night Drive"
            temperatureC != null && temperatureC >= 32.0 -> "High Energy"
            hour in 6..9 -> "Commute"
            hour in 10..16 -> "Focus"
            temperatureC != null && temperatureC <= 10.0 -> "Relax"
            hour in 17..20 -> "Happy"
            else -> "Road Trip"
        }
        val time = when (hour) { in 5..10 -> "morning"; in 11..16 -> "daytime"; in 17..20 -> "evening"; else -> "late night" }
        val weather = temperatureC?.let { "${it.toInt()}°C" } ?: "weather unavailable"
        return MoodSuggestion(mood, "$time · $weather · ${profile(mood)?.themePreset ?: "KILO mix"}")
    }

    /** Personalized reroll used only by the phone's explicit "Choose a mood for me" action. */
    fun suggestForConditions(
        hourOfDay: Int,
        temperatureC: Double?,
        avoidMoods: Set<String>,
        preferredMoods: Set<String>,
        preferredGenres: Set<String>
    ): MoodSuggestion {
        val hour = hourOfDay.coerceIn(0, 23)
        val timeWeights = when (hour) {
            in 5..10 -> mapOf("Commute" to 10, "Coffee Vibes" to 8, "Happy" to 5, "Road Trip" to 4, "Focus" to 3)
            in 11..16 -> mapOf("Focus" to 10, "Coding" to 8, "Coffee Vibes" to 6, "Relax" to 4, "Commute" to 2)
            in 17..20 -> mapOf("Happy" to 9, "Road Trip" to 7, "Relax" to 6, "Workout" to 4, "Romantic" to 4)
            in 21..23 -> mapOf("Night Drive" to 10, "Sleep" to 6, "Gaming" to 5, "Romantic" to 4, "Relax" to 4)
            else -> mapOf("Sleep" to 10, "Night Drive" to 8, "Relax" to 6, "Coding" to 3)
        }
        val favorites = preferredMoods.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT).trim() }
        val genreFavorites = preferredGenres.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT).trim() }
        val excluded = avoidMoods.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT).trim() }
        val ranked = profiles.values.asSequence()
            .filterNot { it.name.lowercase(Locale.ROOT) in excluded }
            .map { profile ->
                var score = (timeWeights[profile.name] ?: 0).toDouble()
                when {
                    temperatureC != null && temperatureC >= 32.0 -> score += when (profile.name) {
                        "High Energy" -> 5.0
                        "Workout" -> 4.0
                        "Party" -> 2.0
                        else -> 0.0
                    }
                    temperatureC != null && temperatureC <= 10.0 -> score += when (profile.name) {
                        "Relax" -> 4.0
                        "Coffee Vibes" -> 3.0
                        "Sleep" -> 2.0
                        else -> 0.0
                    }
                }
                if (profile.name.lowercase(Locale.ROOT) in favorites) score += 9.0
                score += profile.genres.count { it.lowercase(Locale.ROOT) in genreFavorites } * 2.0
                profile to score
            }
            .toList()
        val available = ranked.ifEmpty {
            profiles.values.map { it to 0.0 }
        }
        val highestScore = available.maxOf { it.second }
        // A small choice pool keeps the suggestion relevant to the moment while
        // making repeated taps feel like a real reroll rather than a fixed rule.
        val choicePool = available.filter { it.second >= highestScore - 5.0 }
        val mood = choicePool.random().first.name
        val time = when (hour) { in 5..10 -> "morning"; in 11..16 -> "daytime"; in 17..20 -> "evening"; else -> "late night" }
        val weather = temperatureC?.let { "${it.toInt()}°C" } ?: "weather unavailable"
        val personalCue = if (mood.lowercase(Locale.ROOT) in favorites) " · one of your favorites" else ""
        return MoodSuggestion(mood, "$time · $weather$personalCue · ${profile(mood)?.themePreset ?: "KILO mix"}")
    }
}
