package com.example.kilo

import android.content.Context
import android.util.Log
import com.example.kilo.mood.MoodRecommendationEngine
import com.example.kilo.mood.MoodSessionContext
import com.example.kilo.mood.SongFeatureExtractionPipeline
import com.example.kilo.mood.TrackFeatureDatabase
import com.example.kilo.mood.TrackFeatureInput
import com.example.kilo.mood.isCollectionLikeResult
import com.example.kilo.profile.ListenerProfile
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executor

/** Android Auto adapter for KILO's existing two-stage mood recommendation engine. */
internal object AndroidAutoMoodRecommendationAdapter {
    private const val TAG = "AAMoodRecommender"
    private const val MAX_CANDIDATES = 180
    private const val MAX_AUDIO_LOOKUPS = 60
    private const val MAX_RESULTS = 80
    private const val FEATURE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private val directExecutor = Executor { command -> command.run() }

    /** Called only by Android Auto's background browse builder, never by a library callback. */
    fun recommend(
        context: Context,
        mood: String,
        profile: ListenerProfile,
        excludedIds: Set<String> = emptySet(),
        localCandidates: List<SongItemClean> = emptyList(),
        includeRemoteCandidates: Boolean = true
    ): List<SongItemClean> {
        val app = context.applicationContext
        val favorites = Favorites.songs()
        val preferenceArtists = (profile.followedArtists + favorites.map { it.artist } + PlayedSongHistory.recommendationArtists())
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(8)
        val seeds = MoodRecommendationEngine.candidateSeeds(
            mood = mood,
            favoriteGenres = profile.favoriteGenres,
            preferredArtists = preferenceArtists,
            preferredLanguages = profile.favoriteLanguages,
            limit = 4
        )

        // Stage 1: retrieve a small, mood-compatible and listener-aware candidate pool.
        val queryResults = if (includeRemoteCandidates) seeds.map { seed ->
            seed to runCatching { YtRepo.searchSongs(seed.query) }
                .onFailure { Log.w(TAG, "Candidate retrieval failed mood=$mood query=${seed.query}", it) }
                .getOrDefault(emptyList())
        } else emptyList()
        val retrievalGenres = mutableMapOf<String, MutableSet<String>>()
        queryResults.forEach { (seed, songs) ->
            if (seed.genres.isEmpty()) return@forEach
            songs.forEach { song ->
                (listOf(song.id, song.videoId).filter(String::isNotBlank)).forEach { id ->
                    retrievalGenres.getOrPut(id) { linkedSetOf() }.addAll(seed.genres)
                }
            }
        }

        val hideExplicit = app.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
            .getBoolean("filter_mature_content", true)
        val candidates = (queryResults.flatMap { it.second } + localCandidates)
            .asSequence()
            .filter { it.id.isNotBlank() && it.videoId.isNotBlank() }
            .filter { it.id !in excludedIds && it.videoId !in excludedIds }
            .filter { !hideExplicit || !it.isExplicit }
            .filterNot { isCollectionLikeResult(it.title, it.album, it.durationMs, mood) }
            .distinctBy { it.videoId }
            .take(MAX_CANDIDATES)
            .toList()

        if (candidates.isEmpty()) {
            Log.i(TAG, "MOOD_RANK mood=$mood seeds=${seeds.size} candidates=0 results=0")
            return emptyList()
        }

        // Stage 2: fuse catalog, cached audio analysis, and the listener's own behaviour.
        val events = PlayedSongHistory.listeningEvents(500)
        val likedIds = favorites.flatMap { listOf(it.id, it.videoId) }.filter(String::isNotBlank).toSet()
        val downloadedIds = localCandidates.filter { it.isDownloaded }
            .flatMap { listOf(it.id, it.videoId) }.filter(String::isNotBlank).toSet()
        val now = System.currentTimeMillis()
        val cached = TrackFeatureDatabase.read(app, candidates.map { it.id })
        val audioById = candidates.take(MAX_AUDIO_LOOKUPS).asSequence()
            .filter { song -> cached[song.id]?.let { now - it.updatedAtMs >= FEATURE_MAX_AGE_MS } ?: true }
            .mapNotNull { song ->
                val mediaId = song.videoId.ifBlank { song.id }
                runCatching {
                    PlayerHolder.loadAnalysis(mediaId, directExecutor).get()
                }.getOrNull()?.let { song.id to it }
            }.toMap()

        val inputs = candidates.map { song ->
            val aliases = setOf(song.id, song.videoId).filter(String::isNotBlank).toSet()
            val trackEvents = events.filter { it.trackId in aliases }
            val sourceGenres = (retrievalGenres[song.id].orEmpty() + retrievalGenres[song.videoId].orEmpty())
            TrackFeatureInput(
                id = song.id,
                aliases = aliases,
                title = song.title,
                artist = song.artist,
                album = song.album,
                durationMs = song.durationMs,
                providerGenres = trackEvents.flatMap { it.genres }.toSet(),
                retrievalGenres = sourceGenres,
                languageHint = trackEvents.firstOrNull { it.language.isNotBlank() }?.language
                    ?: CompanionFeatureAdapter.languageOf(song.title),
                audio = audioById[song.id],
                liked = aliases.any { it in likedIds },
                downloaded = aliases.any { it in downloadedIds }
            )
        }
        val profiles = inputs.map { input ->
            cached[input.id]?.takeIf { now - it.updatedAtMs < FEATURE_MAX_AGE_MS }
                ?: SongFeatureExtractionPipeline.extract(input, events, now)
        }
        runCatching { TrackFeatureDatabase.write(app, profiles) }
            .onFailure { Log.w(TAG, "Could not persist mood feature profiles", it) }

        val user = CompanionFeatureAdapter.profile(profile, likedIds, downloadedIds)
            .copy(excludedTrackIds = excludedIds)
        val recentMood = app.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
            .getString("auto_mood_last", "").orEmpty()
        val rankedIds = MoodRecommendationEngine.rank(
            candidates = profiles,
            mood = mood,
            user = user,
            events = events,
            limit = candidates.size,
            context = MoodSessionContext(
                hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                driving = true,
                recentMoods = setOf(recentMood).filter(String::isNotBlank).toSet()
            ),
            nowMs = now,
            retrievalGenresByTrack = retrievalGenres.mapValues { it.value.toSet() }
        )
        val byId = candidates.associateBy { it.id }
        val result = rankedIds.mapNotNull(byId::get).take(MAX_RESULTS)
        Log.i(TAG, "MOOD_RANK mood=$mood seeds=${seeds.size} candidates=${candidates.size} results=${result.size}")
        return result
    }
}
