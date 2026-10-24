package com.example.kilo.journeys

import com.example.kilo.companion.CompanionContext
import com.example.kilo.companion.CompanionProfile
import com.example.kilo.companion.CompanionTrack
import java.util.Locale

data class JourneyPreview(
    val id: String,
    val title: String,
    val subtitle: String,
    val accentArgb: Long,
    val relevance: Int,
    val coverTrackIds: List<String>,
    val trackIds: List<String>
)

data class JourneySession(
    val journeyId: String,
    val startedAtMs: Long,
    val trackIds: List<String>,
    val playedTrackIds: Set<String> = emptySet(),
    val targetTracksAhead: Int = 50,
    val profile: CompanionProfile = CompanionProfile()
)

/** Creates local, evolving listening plans from already available tracks. It never resolves streams. */
object JourneyEngine {
    private data class Template(
        val id: String,
        val title: String,
        val subtitle: String,
        val moods: Set<String>,
        val genres: Set<String>,
        val hours: IntRange,
        val temperatureBias: String = "",
        val discovery: Int,
        val familiarity: Int,
        val accent: Long
    )

    private val templates = listOf(
        Template("night_drive_tokyo", "Night Drive Tokyo", "Neon-lit synths and a steady road rhythm.", setOf("night drive", "midnight cruise"), setOf("synthwave", "electronic", "alternative", "indie", "chill edm"), 20..23, discovery = 35, familiarity = 82, accent = 0xFF7666FF),
        Template("rainy_evening", "Rainy Evening", "Soft, reflective music for a slower evening.", setOf("rain drive", "relax", "reflective"), setOf("ambient", "indie", "acoustic", "jazz", "alternative"), 16..23, "cool", 25, 78, 0xFF5D91B8),
        Template("late_night_coding", "Late Night Coding", "Focused instrumentals with room to think.", setOf("coding", "deep work", "focus"), setOf("lofi", "instrumental", "ambient", "electronic", "jazz"), 20..23, discovery = 25, familiarity = 88, accent = 0xFF7779D8),
        Template("weekend_adventure", "Weekend Adventure", "Upbeat discoveries for getting out of town.", setOf("weekend vibes", "road trip", "discover"), setOf("indie", "pop", "rock", "world music", "electronic"), 8..19, discovery = 78, familiarity = 48, accent = 0xFF4CBA9A),
        Template("coffee_shop_escape", "Coffee Shop Escape", "Warm jazz, acoustic, and easygoing favorites.", setOf("coffee shop", "coffee vibes", "relax"), setOf("jazz", "acoustic", "lofi", "indie", "soul"), 7..16, discovery = 42, familiarity = 72, accent = 0xFFC18A5B),
        Template("sunrise_session", "Sunrise Session", "A gentle start that gradually finds its pace.", setOf("sunrise drive", "happy", "commute"), setOf("acoustic", "indie pop", "soul", "pop"), 5..10, discovery = 48, familiarity = 66, accent = 0xFFE2A74C),
        Template("gym_beast_mode", "Gym Beast Mode", "High-energy tracks for training and momentum.", setOf("workout", "beast mode", "high energy", "running"), setOf("hip-hop", "trap", "phonk", "edm", "rock"), 5..23, discovery = 50, familiarity = 68, accent = 0xFFE95261),
        Template("deep_focus", "Deep Focus", "Low-distraction sound for sustained concentration.", setOf("focus", "study", "deep work", "reading"), setOf("lofi", "instrumental", "classical", "jazz", "ambient"), 8..18, discovery = 22, familiarity = 90, accent = 0xFF5489C8),
        Template("road_to_recovery", "Road To Recovery", "A calm, hopeful soundtrack for a reset.", setOf("healing", "reflective", "hopeful", "relax"), setOf("acoustic", "soul", "ambient", "indie", "classical"), 0..23, discovery = 40, familiarity = 75, accent = 0xFF6AA88E),
        Template("spiritual_morning", "Spiritual Morning", "Devotional and contemplative music for the day ahead.", setOf("devotional", "morning prayer", "mantras & chants"), setOf("hindu devotional", "bhajans", "mantras", "carnatic devotional", "spiritual instrumentals"), 4..11, discovery = 36, familiarity = 83, accent = 0xFFE3A45E)
    )

    fun previews(
        tracks: List<CompanionTrack>,
        profile: CompanionProfile,
        context: CompanionContext,
        limit: Int = 6
    ): List<JourneyPreview> = templates.map { template ->
        val relevance = relevance(template, profile, context)
        val ranked = rankTracks(template, tracks, profile)
        JourneyPreview(
            id = template.id,
            title = template.title,
            subtitle = template.subtitle,
            accentArgb = template.accent,
            relevance = relevance,
            coverTrackIds = ranked.take(3).map { it.id },
            trackIds = ranked.take(50).map { it.id }
        )
    }.filter { it.trackIds.isNotEmpty() }
        .sortedWith(
            compareByDescending<JourneyPreview> { preview -> preview.relevance }
                .thenBy { preview -> templates.indexOfFirst { template -> template.id == preview.id } }
        )
        .take(limit.coerceIn(1, templates.size))

    fun start(
        journeyId: String,
        tracks: List<CompanionTrack>,
        profile: CompanionProfile,
        nowMs: Long = System.currentTimeMillis(),
        targetTracksAhead: Int = 50
    ): JourneySession? {
        val template = templates.firstOrNull { it.id == journeyId } ?: return null
        val ids = rankTracks(template, tracks, profile).take(targetTracksAhead.coerceIn(1, 100)).map { it.id }
        return ids.takeIf { it.isNotEmpty() }?.let {
            JourneySession(journeyId, nowMs, it, targetTracksAhead = targetTracksAhead.coerceIn(1, 100), profile = profile)
        }
    }

    /** Marks a completed item and tops a journey back up from the caller's cached candidate pool. */
    fun advance(session: JourneySession, completedTrackId: String, candidates: List<CompanionTrack>): JourneySession {
        val template = templates.firstOrNull { it.id == session.journeyId } ?: return session
        val played = session.playedTrackIds + completedTrackId
        val completedIndex = session.trackIds.indexOf(completedTrackId)
        val rotated = if (completedIndex >= 0) {
            session.trackIds.drop(completedIndex + 1) + session.trackIds.take(completedIndex)
        } else session.trackIds
        val queued = rotated.filterNot { it in played }
        val needed = (session.targetTracksAhead - queued.size).coerceAtLeast(0)
        if (needed == 0) return session.copy(trackIds = queued, playedTrackIds = played)
        val additions = rankTracks(template, candidates.filterNot { it.id in played || it.id in queued }, session.profile)
            .take(needed).map { it.id }
        return session.copy(trackIds = queued + additions, playedTrackIds = played)
    }

    private fun relevance(template: Template, profile: CompanionProfile, context: CompanionContext): Int {
        val templateMoods = template.moods.map { it.normalized() }.toSet()
        val templateGenres = template.genres.map { it.normalized() }.toSet()
        val moodMatch = profile.favoriteMoods.count { it.normalized() in templateMoods }
        val genreMatch = profile.favoriteGenres.count { it.normalized() in templateGenres }
        val activeMoodMatch = if (context.activeMood.normalized() in templateMoods) 16 else 0
        val hourMatch = if (context.hourOfDay in template.hours) 15 else 0
        val tempMatch = when {
            template.temperatureBias == "cool" && (context.temperatureCelsius ?: 20f) < 16f -> 8
            template.temperatureBias == "warm" && (context.temperatureCelsius ?: 20f) > 27f -> 8
            else -> 0
        }
        val useScore = when (template.id) {
            "night_drive_tokyo" -> profile.nightDriveScore / 8
            "weekend_adventure" -> profile.roadTripScore / 8
            "gym_beast_mode" -> profile.workoutScore / 8
            else -> 0
        }
        val recentPenalty = context.recentMoods.take(3).count { it.normalized() in templateMoods } * 5
        return (50 + moodMatch * 9 + genreMatch * 4 + activeMoodMatch + hourMatch + tempMatch + useScore - recentPenalty).coerceIn(0, 100)
    }

    private fun rankTracks(template: Template, tracks: List<CompanionTrack>, profile: CompanionProfile): List<CompanionTrack> {
        val moods = template.moods.map { it.normalized() }.toSet()
        val genres = template.genres.map { it.normalized() }.toSet()
        val candidates = tracks.asSequence()
            .filter { it.id.isNotBlank() && it.id !in profile.excludedTrackIds }
            .distinctBy { it.id }
            .mapIndexed { index, track ->
                val genreHits = track.genres.count { it.normalized() in genres }
                val moodHits = track.moods.count { it.normalized() in moods }
                val preferredGenreHits = track.genres.count { g -> profile.favoriteGenres.any { it.equals(g, true) } }
                val descriptiveText = "${track.title} ${track.artist} ${track.album}".normalized()
                val textGenreHits = genres.count { it.length >= 3 && descriptiveText.contains(it) }
                val preferredGenreTextHits = profile.favoriteGenres.count { it.length >= 3 && descriptiveText.contains(it.normalized()) }
                val liked = track.isLiked || track.id in profile.likedTrackIds
                val followed = profile.followedArtists.any { it.equals(track.artist, true) }
                val familiarity = if (track.playCount > 0 || liked) template.familiarity / 22.0 else 0.0
                val novelty = if (track.playCount == 0 && !liked) template.discovery / 24.0 else 0.0
                val stale = if (track.lastPlayedAtMs > 0L) ((System.currentTimeMillis() - track.lastPlayedAtMs).coerceAtLeast(0L) / (30L * 24 * 60 * 60 * 1000)).coerceAtMost(6).toDouble() else 0.0
                val score = genreHits * 8.0 + textGenreHits * 3.0 + moodHits * 10.0 + preferredGenreHits * 3.0 + preferredGenreTextHits * 2.0 +
                    (if (followed) 7.0 else 0.0) + (if (liked) 5.0 else 0.0) + familiarity + novelty +
                    track.popularity.coerceIn(0, 100) * 0.04 + track.trendDelta.coerceIn(-10f, 10f) * 0.3 + stale * 0.2 -
                    track.playCount.coerceAtMost(20) * (template.discovery / 800.0) - index * 0.0001
                track to score
            }.sortedByDescending { it.second }.map { it.first }.toList()

        val selected = mutableListOf<CompanionTrack>()
        val artistCounts = mutableMapOf<String, Int>()
        val remaining = candidates.toMutableList()
        while (remaining.isNotEmpty() && selected.size < 50) {
            val bestIndex = remaining.indices.maxByOrNull { index ->
                val track = remaining[index]
                val repeats = artistCounts[track.artist.normalized()] ?: 0
                trackScore(track, genres, moods, profile, template) - repeats * 5.0
            } ?: break
            val track = remaining.removeAt(bestIndex)
            selected += track
            val key = track.artist.normalized()
            artistCounts[key] = (artistCounts[key] ?: 0) + 1
        }
        return selected
    }

    private fun trackScore(track: CompanionTrack, genres: Set<String>, moods: Set<String>, profile: CompanionProfile, template: Template): Double {
        val genreHits = track.genres.count { it.normalized() in genres }
        val moodHits = track.moods.count { it.normalized() in moods }
        val favorite = profile.favoriteGenres.count { g -> track.genres.any { it.equals(g, true) } }
        return genreHits * 8.0 + moodHits * 10.0 + favorite * 3.0 +
            (if (track.isLiked || track.id in profile.likedTrackIds) 5.0 else 0.0) +
            (if (track.playCount == 0) template.discovery / 25.0 else template.familiarity / 25.0)
    }

    private fun String.normalized(): String = lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
}
