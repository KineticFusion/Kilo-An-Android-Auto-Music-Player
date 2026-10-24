package com.example.kilo.timemachine

import com.example.kilo.companion.ListeningEvent
import java.util.Calendar
import java.util.Locale

data class TimeCapsule(
    val id: String, val title: String, val fromMs: Long, val toMs: Long, val trackIds: List<String>,
    val topArtists: List<String>, val topMoods: List<String>, val topGenres: List<String>, val listenCount: Int
)

/** Creates history summaries from locally stored events without any playback dependencies. */
object TimeMachine {
    fun capsules(events: List<ListeningEvent>, nowMs: Long = System.currentTimeMillis()): List<TimeCapsule> {
        if (events.isEmpty()) return emptyList()
        val calendar = Calendar.getInstance().apply { timeInMillis = nowMs }
        val startOfYear = (calendar.clone() as Calendar).apply {
            set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val startOfLastYear = (calendar.clone() as Calendar).apply {
            add(Calendar.YEAR, -1)
            set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        fun at(year: Int, month: Int, day: Int): Long = (calendar.clone() as Calendar).apply {
            set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val summerYear = if (month < Calendar.JUNE) year - 1 else year
        val winterYear = if (month >= Calendar.DECEMBER) year else year - 1
        val ranges = listOf(
            Triple("last_30_days", "Last 30 days", nowMs - 30L * DAY),
            Triple("last_6_months", "Last 6 months", nowMs - 183L * DAY),
            Triple("this_year", "My ${calendar.get(Calendar.YEAR)}", startOfYear),
            Triple("last_year", "Last year", startOfLastYear),
            Triple("my_summer", "My Summer $summerYear", at(summerYear, Calendar.JUNE, 1)),
            Triple("my_winter", "My Winter", at(winterYear, Calendar.DECEMBER, 1))
        )
        return ranges.mapNotNull { (id, title, from) ->
            val until = when (id) {
                "last_year" -> startOfYear
                "my_summer" -> at(summerYear, Calendar.SEPTEMBER, 1).coerceAtMost(nowMs + 1)
                "my_winter" -> at(winterYear + 1, Calendar.MARCH, 1).coerceAtMost(nowMs + 1)
                else -> nowMs + 1
            }
            val scoped = events.filter { it.atMs >= from && it.atMs < until && it.action !in setOf("search", "skip", "unlike") }
            if (scoped.isEmpty()) return@mapNotNull null
            fun top(values: Iterable<String>): List<String> = values.map(String::trim).filter(String::isNotBlank)
                .groupingBy { it.lowercase(Locale.ROOT) }.eachCount().entries.sortedByDescending { it.value }
                .take(5).map { it.key.replaceFirstChar { ch -> ch.uppercase() } }
            TimeCapsule(id, title, from, until,
                scoped.groupingBy { it.trackId }.eachCount().entries.sortedByDescending { it.value }.take(20).map { it.key },
                top(scoped.map { it.artist }), top(scoped.map { it.mood }), top(scoped.flatMap { it.genres }), scoped.size)
        }
    }

    private const val DAY = 24L * 60 * 60 * 1000
}
