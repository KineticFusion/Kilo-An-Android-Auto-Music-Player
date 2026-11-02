package com.example.kilo

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Builds a small time- and taste-aware Browse row using only local profile signals. */
internal object BrowseDailyCollectionEngine {
    data class Collection(val title: String, val subtitle: String, val query: String)

    fun today(
        hour: Int,
        favoriteGenres: kotlin.collections.Collection<String>,
        favoriteMoods: kotlin.collections.Collection<String>,
        followedArtists: kotlin.collections.Collection<String>,
        date: Calendar = Calendar.getInstance()
    ): List<Collection> {
        val genre = favoriteGenres.firstOrNull(String::isNotBlank)?.trim()
        val mood = favoriteMoods.firstOrNull(String::isNotBlank)?.trim()
        val artist = followedArtists.firstOrNull(String::isNotBlank)?.trim()
        val moment = when (hour.coerceIn(0, 23)) {
            in 5..10 -> "Morning"
            in 11..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Late Night"
        }
        val weekday = SimpleDateFormat("EEEE", Locale.getDefault()).format(date.time)
        val genreQuery = genre?.let { "$it new songs" } ?: "new songs for you"
        val moodQuery = mood?.let { "$it music mix" } ?: "music for $moment"
        val artistQuery = artist?.let { "new music like $it" } ?: "emerging artists new music"
        return listOf(
            Collection("$moment Finds", "A fresh set for this time of day", moodQuery),
            Collection("New in ${genre ?: "your sound"}", "Recent tracks close to your taste", genreQuery),
            Collection("Around $weekday", "A new daily discovery route", "${weekday.lowercase(Locale.ROOT)} music discoveries"),
            Collection("Near Your Favorites", "New music with a familiar thread", artistQuery)
        ).distinctBy { it.query.lowercase(Locale.ROOT) }
    }
}
