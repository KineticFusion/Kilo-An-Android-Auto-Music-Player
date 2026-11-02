package com.example.kilo

import android.content.Context
import android.util.Log
import com.example.kilo.recommendation.GenomeXRecommendationEngine
import com.example.kilo.mood.MoodRecommendationEngine
import com.example.kilo.mood.MoodSessionContext
import com.example.kilo.mood.MoodCandidateSeed
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
        includeRemoteCandidates: Boolean = true,
        targetGenre: String? = null
    ): List<SongItemClean> {
        val app = context.applicationContext
        val favorites = Favorites.songs()
        val preferenceArtists = (profile.followedArtists + favorites.map { it.artist } + PlayedSongHistory.recommendationArtists())
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(8)
        val languageBrowse = targetGenre?.takeIf(BrowseLanguageFilter::isLanguage)
        val seeds = if (languageBrowse != null) {
            BrowseLanguageFilter.discoveryQueries(languageBrowse).map { query ->
                MoodCandidateSeed(query = query, language = languageBrowse)
            }
        } else if (targetGenre.isNullOrBlank()) {
            MoodRecommendationEngine.candidateSeeds(
                mood = mood,
                favoriteGenres = profile.favoriteGenres,
                preferredArtists = preferenceArtists,
                preferredLanguages = profile.favoriteLanguages,
                limit = 4
            )
        } else {
            MoodRecommendationEngine.genreCandidateSeeds(
                genre = targetGenre,
                preferredArtists = preferenceArtists,
                preferredLanguages = profile.favoriteLanguages,
                limit = 3
            )
        }

        // Stage 1: retrieve a small, mood-compatible and listener-aware candidate pool.
        var queryResults = if (includeRemoteCandidates) seeds.map { seed ->
            seed to runCatching { YtRepo.searchSongs(seed.query) }
                .onFailure { Log.w(TAG, "Candidate retrieval failed mood=$mood query=${seed.query}", it) }
                .getOrDefault(emptyList())
        } else emptyList()
        val hideExplicit = app.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
            .getBoolean("filter_mature_content", true)
        fun collectEligible(rows: List<Pair<MoodCandidateSeed, List<SongItemClean>>>): List<SongItemClean> =
            (rows.flatMap { it.second } + localCandidates)
                .asSequence()
                .filter { it.id.isNotBlank() && it.videoId.isNotBlank() }
                .filter { it.id !in excludedIds && it.videoId !in excludedIds }
                .filter { !hideExplicit || !it.isExplicit }
                .filterNot { isCollectionLikeResult(it.title, it.album, it.durationMs, mood) }
                .filterNot { !targetGenre.isNullOrBlank() && it.title.trim().equals(targetGenre.trim(), ignoreCase = true) }
                .filterNot { targetGenre.isNullOrBlank() && it.title.trim().equals(mood.trim(), ignoreCase = true) }
                .distinctBy { it.videoId }
                .take(MAX_CANDIDATES)
                .toList()
        fun applyCategoryConstraint(songs: List<SongItemClean>): List<SongItemClean> =
            if (!languageBrowse.isNullOrBlank()) BrowseLanguageFilter.filter(songs, languageBrowse) else songs

        var candidates = applyCategoryConstraint(collectEligible(queryResults))
        if (includeRemoteCandidates && candidates.size < 12) {
            val fallbackQueries = when {
                !languageBrowse.isNullOrBlank() -> listOf(
                    "${languageBrowse} popular songs", "best ${languageBrowse} songs", "${languageBrowse} hits"
                )
                !targetGenre.isNullOrBlank() -> listOf(
                    "${targetGenre} popular songs", "best ${targetGenre} hits", "${targetGenre} music mix"
                )
                else -> moodDiscoveryQueries(mood, profile.favoriteGenres, preferenceArtists) + "$mood mood songs"
            }.map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase(Locale.ROOT) }
                .filterNot { query -> seeds.any { it.query.equals(query, ignoreCase = true) } }
                .take(4)
            val fallbackSeeds = fallbackQueries.map { query ->
                MoodCandidateSeed(
                    query = query,
                    genres = targetGenre?.takeIf(String::isNotBlank)?.let { setOf(it) }
                        ?: SmartMoodEngine.profile(mood)?.genres.orEmpty().toSet(),
                    language = languageBrowse.orEmpty()
                )
            }
            val fallbackResults = fallbackSeeds.map { seed ->
                seed to runCatching { YtRepo.searchSongs(seed.query) }
                    .onFailure { Log.w(TAG, "Fallback retrieval failed mood=$mood query=${seed.query}", it) }
                    .getOrDefault(emptyList())
            }
            queryResults = queryResults + fallbackResults
            candidates = applyCategoryConstraint(collectEligible(queryResults))
            Log.i(TAG, "SPARSE_RESULTS_EXPANDED mood=$mood category=${targetGenre.orEmpty()} " +
                "queries=${fallbackResults.size} candidates=${candidates.size}")
        }
        val retrievalGenres = mutableMapOf<String, MutableSet<String>>()
        queryResults.forEach { (seed, songs) ->
            if (seed.genres.isEmpty()) return@forEach
            songs.forEach songLoop@{ song ->
                if (seed.artists.isNotEmpty() && seed.artists.none { expected ->
                        val artist = song.artist.lowercase(Locale.ROOT)
                        val name = expected.lowercase(Locale.ROOT)
                        artist == name || artist.contains(name) || name.contains(artist)
                    }) return@songLoop
                (listOf(song.id, song.videoId).filter(String::isNotBlank)).forEach { id ->
                    retrievalGenres.getOrPut(id) { linkedSetOf() }.addAll(seed.genres)
                }
            }
        }

        // Regional entries share the genre browse adapter in Android Auto. Keep a
        // recognized language as a hard constraint there too, before genome ranking.
        if (!targetGenre.isNullOrBlank() && BrowseLanguageFilter.isLanguage(targetGenre)) {
            Log.d(TAG, "LANGUAGE_FILTER language=$targetGenre kept=${candidates.size}")
        }

        PlaybackRepository.rememberAll(candidates)
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
            TrackFeatureInput(
                id = song.id,
                aliases = aliases,
                title = song.title,
                artist = song.artist,
                album = song.album,
                durationMs = song.durationMs,
                providerGenres = trackEvents.flatMap { it.genres }.toSet(),
                // Keep search-query hints out of the durable feature cache. They are passed to
                // the ranker only for this result set, avoiding stale genre labels next session.
                retrievalGenres = emptySet(),
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
        val moodVectorProfile = if (targetGenre.isNullOrBlank()) {
            MoodRecommendationEngine.profile(mood, user.favoriteGenres)
        } else null
        val rankedIds = GenomeXRecommendationEngine.rank(
            context = app,
            profiles = profiles,
            albums = candidates.associate { it.id to it.album },
            mood = moodVectorProfile,
            genre = targetGenre,
            user = user,
            events = events,
            playedIds = PlayedSongHistory.recentIds(),
            driving = true,
            sessionId = "android_auto",
            limit = candidates.size,
            genresByTrack = retrievalGenres
        )
        val byId = candidates.associateBy { it.id }
        val ranked = rankedIds.mapNotNull(byId::get)
        val rankedKeys = ranked.mapTo(mutableSetOf()) { it.videoId.ifBlank { it.id } }
        // Keep the personalized order, then fill from the same mood/category-matched
        // retrieval pool when strict genome ranking yields only a handful of tracks.
        val result = (ranked + candidates.filterNot { it.videoId.ifBlank { it.id } in rankedKeys })
            .distinctBy { it.videoId.ifBlank { it.id } }
            .take(MAX_RESULTS)
        Log.i(TAG, "MOOD_RANK mood=$mood seeds=${seeds.size} candidates=${candidates.size} results=${result.size}")
        return result
    }
}
