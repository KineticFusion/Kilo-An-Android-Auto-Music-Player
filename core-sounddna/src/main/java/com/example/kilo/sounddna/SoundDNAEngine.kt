package com.example.kilo.sounddna

import com.example.kilo.companion.CompanionProfile
import com.example.kilo.companion.ListeningEvent
import java.util.Calendar
import java.util.Locale

data class SoundDNASnapshot(
    val topGenres: List<String>, val topArtists: List<String>, val topMoods: List<String>, val topLanguages: List<String>,
    val favoriteHour: Int?, val favoriteDay: String?, val discoveryScore: Int, val replayScore: Int,
    val roadTripScore: Int, val nightDriveScore: Int, val workoutScore: Int, val personality: String, val tracksAnalyzed: Int
)

/** Analyzes local listening signals only; it performs no I/O or network requests. */
object SoundDNAEngine {
    fun analyze(events: List<ListeningEvent>, profile: CompanionProfile, nowMs: Long = System.currentTimeMillis()): SoundDNASnapshot {
        val listening = events.filter { it.action !in setOf("search", "skip", "unlike") }
        fun top(values: Iterable<String>, fallback: Iterable<String> = emptyList()): List<String> = values
            .map(String::trim).filter(String::isNotBlank).groupingBy { it.lowercase(Locale.ROOT) }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key.replaceFirstChar { ch -> ch.uppercase() } }
            .ifEmpty { fallback.map(String::trim).filter(String::isNotBlank).distinct().take(5) }.take(5)
        val artists = top(listening.map { it.artist }, profile.followedArtists)
        val genres = top(listening.flatMap { it.genres }, profile.favoriteGenres)
        val moods = top(listening.map { it.mood }, profile.favoriteMoods)
        val languages = top(listening.map { it.language }, profile.favoriteLanguages)
        val hours = listening.groupingBy { Calendar.getInstance().apply { timeInMillis = it.atMs }.get(Calendar.HOUR_OF_DAY) }.eachCount()
        val days = listening.groupingBy { Calendar.getInstance().apply { timeInMillis = it.atMs }.get(Calendar.DAY_OF_WEEK) }.eachCount()
        val dayNames = listOf("", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val trackCounts = listening.groupingBy { it.trackId }.eachCount()
        val recentListening = listening.filter { it.atMs >= nowMs - 180L * 24 * 60 * 60 * 1000 }
        val discoverySample = recentListening.ifEmpty { listening }
        val discovery = if (discoverySample.isEmpty()) 0 else (discoverySample.map { it.trackId }.distinct().size * 100 / discoverySample.size).coerceIn(0, 100)
        val replay = if (trackCounts.isEmpty()) 0 else (trackCounts.values.count { it > 1 } * 100 / trackCounts.size).coerceIn(0, 100)
        fun hour(event: ListeningEvent) = Calendar.getInstance().apply { timeInMillis = event.atMs }.get(Calendar.HOUR_OF_DAY)
        val night = listening.count { hour(it) in 20..23 }
        val commute = listening.count { hour(it) in 6..10 }
        val workout = listening.count { it.mood.contains("workout", true) || it.mood.contains("gym", true) }
        val road = listening.count { it.mood.contains("road trip", true) || it.mood.contains("commute", true) }
        val personality = when {
            listening.size < 5 -> "Your sound is taking shape"
            discovery >= 72 -> "Global Discoverer"
            replay >= 55 -> "Favorite Replayer"
            night > commute -> "Night Explorer"
            road > 2 -> "Road Warrior"
            workout > 2 -> "Energy Seeker"
            else -> "Sound Explorer"
        }
        return SoundDNASnapshot(genres, artists, moods, languages, hours.maxByOrNull { it.value }?.key,
            days.maxByOrNull { it.value }?.key?.let(dayNames::getOrNull), discovery, replay,
            (profile.roadTripScore + road * 4).coerceIn(0, 100), (profile.nightDriveScore + night * 3).coerceIn(0, 100),
            (profile.workoutScore + workout * 5).coerceIn(0, 100), personality, listening.size)
    }
}
