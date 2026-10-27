package com.example.kilo.mood

import android.content.Context
import android.util.LruCache
import com.example.kilo.audioanalysis.TrackAnalysisEntity
import com.example.kilo.companion.CompanionProfile
import com.example.kilo.companion.ListeningEvent
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** Inputs are existing catalog metadata plus optional already-cached audio analysis. */
data class TrackFeatureInput(
    val id: String,
    val aliases: Set<String> = emptySet(),
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val providerGenres: Set<String> = emptySet(),
    /** Genre seed attached by candidate retrieval, never inferred from the track title. */
    val retrievalGenres: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val languageHint: String = "",
    val audio: TrackAnalysisEntity? = null,
    val liked: Boolean = false,
    val downloaded: Boolean = false
)

data class TrackFeatureProfile(
    val trackId: String,
    val artistKey: String,
    val genres: Map<String, Float>,
    val languages: Map<String, Float>,
    val attributes: Map<String, Float>,
    val completionRate: Float,
    val replayRate: Float,
    val skipRate: Float,
    val metadataConfidence: Float,
    val isCollection: Boolean,
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class MoodFeatureProfile(
    val name: String,
    val attributes: Map<String, Float>,
    val genres: Map<String, Float>,
    val relatedMoods: Set<String> = emptySet()
)

/** Candidate provenance used by the retrieval stage of the recommender. */
data class MoodCandidateSeed(
    val query: String,
    val genres: Set<String> = emptySet(),
    val artists: Set<String> = emptySet(),
    val language: String = ""
)

data class MoodSessionContext(
    val hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    val driving: Boolean = false,
    val recentMoods: Set<String> = emptySet()
)

data class MetadataSignals(
    val genres: Map<String, Float>,
    val languages: Map<String, Float>,
    val tags: Set<String>,
    val isCollection: Boolean,
    val confidence: Float
)
data class AudioSignals(val attributes: Map<String, Float>)
data class BehaviorSignals(
    val attributes: Map<String, Float>,
    val completion: Float,
    val replay: Float,
    val skip: Float
)

/** Rejects obvious compilation uploads masquerading as tracks in search results. */
fun isCollectionLikeResult(title: String, album: String = "", durationMs: Long = 0L, mood: String = ""): Boolean {
    val text = "$title $album".lowercase(Locale.ROOT)
    val normalizedTitle = title.lowercase(Locale.ROOT)
    val collectionMarkers = Regex(
        "\\b(mix|playlist|compilation|full album|best of|top\\s+\\d+|non[\\s-]?stop|continuous mix|mega ?mix|medley|mashup|dj set|live set|hour[\\s-]?long|background music|\\bbgm\\b|study beats|focus beats|sleep sounds|relaxing sounds|study session|focus session|music for (study|focus|sleep|relaxation|meditation))\\b",
        RegexOption.IGNORE_CASE
    )
    val nonEditRadioLabel = Regex("\\bradio(?!\\s+(edit|version))\\b", RegexOption.IGNORE_CASE)
    val moodPhrase = mood.trim().lowercase(Locale.ROOT)
    val moodPattern = moodPhrase.split(Regex("\\s+")).filter(String::isNotBlank)
        .joinToString("\\s+") { Regex.escape(it) }
    val titleLooksLikeMoodSeo = moodPhrase.isNotBlank() &&
        Regex("\\b$moodPattern\\b", RegexOption.IGNORE_CASE).containsMatchIn(normalizedTitle) &&
        Regex("\\b(synthwave|electronic|indie|instrumental|lofi|lo-fi|ambient|chill|edm|focus|study|relax|driving|workout|beats|vibes)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalizedTitle)
    return collectionMarkers.containsMatchIn(text) || nonEditRadioLabel.containsMatchIn(text) ||
        titleLooksLikeMoodSeo || durationMs >= 15L * 60L * 1000L
}

/** Layer 1: use provider genre/tag metadata only. Titles never create mood or genre scores. */
object MetadataAnalyzer {
    private val taxonomy = linkedMapOf(
        "synthwave" to listOf("synthwave", "retrowave", "synth wave"),
        "electronic" to listOf("electronic", "electronica", "edm", "electro"),
        "alternative" to listOf("alternative", "alt rock", "alt-pop"),
        "indie" to listOf("indie", "independent"),
        "chill edm" to listOf("chill edm", "melodic edm", "chillstep"),
        "hip-hop" to listOf("hip-hop", "hip hop", "rap", "trap", "phonk"),
        "pop" to listOf("pop", "dance pop", "synth-pop"),
        "rock" to listOf("rock", "metal", "punk"),
        "lofi" to listOf("lofi", "lo-fi", "jazzhop", "chillhop"),
        "instrumental" to listOf("instrumental", "no vocals", "piano solo"),
        "jazz" to listOf("jazz", "smooth jazz", "bebop"),
        "ambient" to listOf("ambient", "drone", "soundscape"),
        "classical" to listOf("classical", "orchestral", "piano"),
        "acoustic" to listOf("acoustic", "unplugged", "folk"),
        "dance" to listOf("dance", "house", "techno", "disco"),
        "r&b" to listOf("r&b", "rnb", "soul", "neo soul"),
        "soundtrack" to listOf("soundtrack", "score", "film theme"),
        "country" to listOf("country", "bluegrass")
    )

    fun analyze(input: TrackFeatureInput): MetadataSignals {
        val genreScores = linkedMapOf<String, Float>()
        val providerText = input.providerGenres.joinToString(" ").normalize()
        val tagsText = input.tags.joinToString(" ").normalize()
        val retrievalText = input.retrievalGenres.joinToString(" ").normalize()
        taxonomy.forEach { (genre, variants) ->
            if (variants.any { providerText.contains(it.normalize()) }) genreScores[genre] = 1f
            else if (variants.any { tagsText.contains(it.normalize()) }) genreScores[genre] = 0.78f
            else if (variants.any { retrievalText.contains(it.normalize()) }) genreScores[genre] = 0.58f
        }

        val tags = input.tags.mapTo(linkedSetOf()) { it.normalize() }
        val language = LanguageAnalyzer.analyze(input.title, input.artist, input.album, input.languageHint)
        val collection = isCollectionLikeResult(input.title, input.album, input.durationMs)
        val confidence = when {
            genreScores.values.any { it >= 0.98f } -> 0.98f
            genreScores.values.any { it >= 0.75f } -> 0.78f
            genreScores.values.any { it >= 0.55f } -> 0.55f
            genreScores.isNotEmpty() -> 0.24f
            else -> 0.08f
        }
        return MetadataSignals(genreScores, language, tags, collection, confidence)
    }
}

/** Layer 2: consumes existing analysis only; it never reads or intercepts active playback audio. */
object AudioAnalyzer {
    fun analyze(input: TrackFeatureInput, genres: Map<String, Float>, tags: Set<String>): AudioSignals {
        val attrs = linkedMapOf<String, Float>()
        input.audio?.let { analysis ->
            attrs["energy"] = analysis.energy.toFloat().coerceIn(0f, 100f)
            analysis.bpm?.let { attrs["tempo"] = ((it - 55.0) / 145.0 * 100.0).toFloat().coerceIn(0f, 100f) }
            attrs["danceability"] = analysis.danceability.toFloat().coerceIn(0f, 100f)
            attrs["loudness"] = ((analysis.loudnessDb + 60.0) / 60.0 * 100.0).toFloat().coerceIn(0f, 100f)
            analysis.musicalKey?.let { attrs["darkness"] = if (it.contains("minor", true)) 72f else 36f }
            when (analysis.mood.lowercase(Locale.ROOT)) {
                "calm", "mellow" -> attrs["focus"] = 72f
                "high energy", "upbeat" -> attrs["workout"] = 72f
            }
        }
        fun genre(name: String) = genres.entries.firstOrNull { it.key.equals(name, true) }?.value ?: 0f
        val instrumental = maxOf(
            if (tags.any { "instrumental" in it || "no vocals" in it }) 0.92f else 0f,
            genre("ambient") * 0.86f, genre("classical") * 0.82f, genre("lofi") * 0.68f,
            genre("instrumental")
        )
        if (instrumental > 0f) attrs["instrumentalness"] = instrumental * 100f
        val atmosphere = maxOf(genre("ambient") * 0.95f, genre("synthwave") * 0.72f,
            genre("electronic") * 0.58f, genre("jazz") * 0.52f, genre("soundtrack") * 0.88f)
        if (atmosphere > 0f) attrs["atmosphere"] = atmosphere * 100f
        val inferredEnergy = maxOf(genre("dance") * 0.78f, genre("hip-hop") * 0.76f,
            genre("rock") * 0.78f, genre("ambient") * 0.24f, genre("classical") * 0.28f)
        if (attrs["energy"] == null && inferredEnergy > 0f) attrs["energy"] = inferredEnergy * 100f
        val inferredDance = maxOf(genre("dance") * 0.88f, genre("pop") * 0.64f, genre("hip-hop") * 0.70f)
        if (attrs["danceability"] == null && inferredDance > 0f) attrs["danceability"] = inferredDance * 100f
        if (attrs["tempo"] == null && genres.isNotEmpty()) attrs["tempo"] = when {
            genre("ambient") > 0f || genre("classical") > 0f -> 25f
            genre("dance") > 0f || genre("hip-hop") > 0f -> 72f
            genre("synthwave") > 0f || genre("pop") > 0f -> 58f
            else -> 46f
        }
        if (attrs["darkness"] == null) {
            val darkness = maxOf(genre("synthwave") * 0.76f, genre("alternative") * 0.56f, genre("ambient") * 0.60f)
            if (darkness > 0f) attrs["darkness"] = darkness * 100f
        }
        return AudioSignals(attrs)
    }
}

/** Layer 3: turns listens, completions, replays, skips and likes into per-track signals. */
object BehaviorAnalyzer {
    fun analyze(input: TrackFeatureInput, events: List<ListeningEvent>, nowMs: Long = System.currentTimeMillis()): BehaviorSignals {
        val ids = input.aliases + input.id
        val matching = events.filter { it.trackId in ids }
        val recent = matching.filter { it.atMs >= nowMs - 180L * DAY_MS }
        val complete = recent.count { it.action == "complete" }
        val listen = recent.count { it.action == "listen" }
        val replay = recent.count { it.action == "replay" }
        val skip = recent.count { it.action == "skip" }
        val completion = if (complete + listen + skip == 0) 0f
            else ((complete + listen * 0.55f) / (complete + listen + skip)).coerceIn(0f, 1f)
        val sessions = (complete + listen + replay + skip).coerceAtLeast(1)
        val replayRate = (replay.toFloat() / sessions).coerceIn(0f, 1f)
        val skipRate = (skip.toFloat() / sessions).coerceIn(0f, 1f)
        val attrs = linkedMapOf<String, Float>()
        if (matching.isNotEmpty()) {
            attrs["familiarity"] = (matching.size.coerceAtMost(8) / 8f) * 100f
            attrs["discovery"] = 100f - attrs.getValue("familiarity")
            attrs["replay"] = replayRate * 100f
            val nightShare = recent.count { event ->
                val hour = Calendar.getInstance().apply { timeInMillis = event.atMs }.get(Calendar.HOUR_OF_DAY)
                hour >= 20 || hour < 5
            }.toFloat() / recent.size.coerceAtLeast(1)
            if (recent.isNotEmpty()) attrs["nightDrive"] = nightShare * 100f
            if (recent.any { it.mood.contains("workout", true) || it.mood.contains("gym", true) }) attrs["workout"] = 70f
            if (recent.any { it.mood.contains("focus", true) || it.mood.contains("study", true) }) attrs["focus"] = 70f
        }
        if (input.liked || recent.any { it.action == "like" }) attrs["affinity"] = 100f
        return BehaviorSignals(attrs, completion, replayRate, skipRate)
    }
    private const val DAY_MS = 24L * 60L * 60L * 1000L
}

/** Layer 4: explicit language wins; script recognition is a low-confidence fallback. */
object LanguageAnalyzer {
    fun analyze(title: String, artist: String, album: String, hint: String = ""): Map<String, Float> {
        if (hint.isNotBlank()) return mapOf(hint.trim() to 0.98f)
        val codePoints = "$title $artist $album".codePoints().toArray()
        fun has(range: IntRange) = codePoints.any { it in range }
        val language = when {
            has(0x0B80..0x0BFF) -> "Tamil"
            has(0x0C00..0x0C7F) -> "Telugu"
            has(0x0C80..0x0CFF) -> "Kannada"
            has(0x0D00..0x0D7F) -> "Malayalam"
            has(0x0980..0x09FF) -> "Bengali"
            has(0x0A00..0x0A7F) -> "Punjabi"
            has(0x0900..0x097F) -> "Hindi"
            has(0x3040..0x30FF) -> "Japanese"
            has(0xAC00..0xD7AF) -> "Korean"
            has(0x0600..0x06FF) -> "Arabic"
            else -> ""
        }
        return if (language.isEmpty()) emptyMap() else mapOf(language to 0.72f)
    }
}

/** Layer 5: fuses independent signals without using the selected mood text as a track match. */
object FeatureFusionEngine {
    fun fuse(input: TrackFeatureInput, metadata: MetadataSignals, audio: AudioSignals,
             behavior: BehaviorSignals, nowMs: Long = System.currentTimeMillis()): TrackFeatureProfile {
        val attrs = linkedMapOf<String, Float>().apply {
            putAll(audio.attributes)
            putAll(behavior.attributes)
        }
        fun genre(name: String) = metadata.genres.entries.firstOrNull { it.key.equals(name, true) }?.value ?: 0f
        if (attrs["focus"] == null && (genre("lofi") > 0 || genre("ambient") > 0 || genre("classical") > 0)) {
            attrs["focus"] = ((attrs["instrumentalness"] ?: 45f) * 0.65f + (100f - (attrs["energy"] ?: 45f)) * 0.35f)
        }
        if (attrs["nightDrive"] == null && genre("synthwave") + genre("electronic") + genre("alternative") > 0f) {
            attrs["nightDrive"] = maxOf(genre("synthwave") * 100f, genre("electronic") * 76f, genre("alternative") * 54f)
        }
        if (attrs["workout"] == null && genre("dance") + genre("hip-hop") + genre("rock") > 0f) {
            attrs["workout"] = maxOf(attrs["energy"] ?: 55f, genre("dance") * 100f, genre("hip-hop") * 88f)
        }
        return TrackFeatureProfile(
            trackId = input.id,
            artistKey = input.artist.normalize(),
            genres = metadata.genres,
            languages = metadata.languages,
            attributes = attrs.mapValues { it.value.coerceIn(0f, 100f) },
            completionRate = behavior.completion,
            replayRate = behavior.replay,
            skipRate = behavior.skip,
            metadataConfidence = metadata.confidence,
            isCollection = metadata.isCollection,
            updatedAtMs = nowMs
        )
    }
}

object SongFeatureExtractionPipeline {
    fun extract(input: TrackFeatureInput, events: List<ListeningEvent>, nowMs: Long = System.currentTimeMillis()): TrackFeatureProfile {
        val metadata = MetadataAnalyzer.analyze(input)
        val behavior = BehaviorAnalyzer.analyze(input, events, nowMs)
        val audio = AudioAnalyzer.analyze(input, metadata.genres, metadata.tags)
        return FeatureFusionEngine.fuse(input, metadata, audio, behavior, nowMs)
    }
}

/** Bounded local feature cache. It is independent of playback and the music catalog. */
object TrackFeatureDatabase {
    private const val PREFS = "kilo_track_feature_profiles_v3"
    private const val INDEX = "track_ids"
    private const val CAPACITY = 500
    private val memory = object : LruCache<String, TrackFeatureProfile>(CAPACITY) {}

    fun read(context: Context, ids: Collection<String>): Map<String, TrackFeatureProfile> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ids.asSequence().filter(String::isNotBlank).distinct().mapNotNull { id ->
            val cached = synchronized(memory) { memory.get(id) }
            val profile = cached ?: prefs.getString("track:$id", null)?.let { decode(id, it) }
            if (profile == null) null else {
                synchronized(memory) { memory.put(id, profile) }
                id to profile
            }
        }.toMap()
    }

    fun write(context: Context, profiles: Collection<TrackFeatureProfile>) {
        if (profiles.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val indexed = prefs.getStringSet(INDEX, emptySet()).orEmpty().toMutableSet()
        val editor = prefs.edit()
        profiles.filter { it.trackId.isNotBlank() }.forEach { profile ->
            indexed += profile.trackId
            synchronized(memory) { memory.put(profile.trackId, profile) }
            editor.putString("track:${profile.trackId}", encode(profile))
        }
        val recentIds = (profiles.sortedByDescending { it.updatedAtMs }.map { it.trackId } + indexed).distinct().take(CAPACITY)
        (indexed - recentIds.toSet()).forEach { id ->
            editor.remove("track:$id")
            synchronized(memory) { memory.remove(id) }
        }
        editor.putStringSet(INDEX, recentIds.toSet()).apply()
    }

    private fun encode(p: TrackFeatureProfile) = JSONObject()
        .put("artist", p.artistKey).put("genres", p.genres.toJson()).put("languages", p.languages.toJson())
        .put("attributes", p.attributes.toJson()).put("completion", p.completionRate).put("replay", p.replayRate)
        .put("skip", p.skipRate).put("confidence", p.metadataConfidence).put("collection", p.isCollection)
        .put("updated", p.updatedAtMs).toString()

    private fun decode(id: String, raw: String): TrackFeatureProfile? = runCatching {
        val j = JSONObject(raw)
        TrackFeatureProfile(id, j.optString("artist"), j.optJSONObject("genres").floatMap(),
            j.optJSONObject("languages").floatMap(), j.optJSONObject("attributes").floatMap(),
            j.optDouble("completion").toFloat(), j.optDouble("replay").toFloat(), j.optDouble("skip").toFloat(),
            j.optDouble("confidence").toFloat(), j.optBoolean("collection"), j.optLong("updated"))
    }.getOrNull()

    private fun Map<String, Float>.toJson() = JSONObject().also { json -> forEach { (key, value) -> json.put(key, value) } }
    private fun JSONObject?.floatMap(): Map<String, Float> = this?.let { obj ->
        buildMap { val keys = obj.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, obj.optDouble(key).toFloat()) } }
    }.orEmpty()
}

/** Layer 6: applies lightweight session context to the mood target, never to track text. */
object MoodContextAnalyzer {
    fun adjust(base: MoodFeatureProfile, context: MoodSessionContext): MoodFeatureProfile {
        val attrs = base.attributes.toMutableMap()
        val isNight = context.hourOfDay >= 20 || context.hourOfDay < 5
        if (isNight && base.name.equals("Night Drive", true)) {
            attrs["atmosphere"] = maxOf(attrs["atmosphere"] ?: 0f, 94f)
        }
        if (context.driving && base.name in setOf("Road Trip", "Commute", "Night Drive")) {
            attrs["driving"] = 100f
        }
        if (context.recentMoods.any { it.equals(base.name, true) }) {
            attrs["familiarity"] = maxOf(attrs["familiarity"] ?: 0f, 58f)
        }
        return base.copy(attributes = attrs)
    }
}
/** Mood targets, candidate discovery, taste scoring and diversity. Mood words never score a track. */
object MoodRecommendationEngine {
    private fun mood(name: String, attrs: Map<String, Float>, vararg genres: Pair<String, Float>, related: Set<String> = emptySet()) =
        MoodFeatureProfile(name, attrs, genres.toMap(), related)

    private val profiles = listOf(
        mood("Night Drive", mapOf("nightDrive" to 100f, "atmosphere" to 90f, "energy" to 60f, "darkness" to 70f, "tempo" to 50f),
            "synthwave" to .95f, "electronic" to .75f, "alternative" to .55f, "indie" to .50f, "chill edm" to .45f, related = setOf("Relax", "Road Trip")),
        mood("Workout", mapOf("workout" to 100f, "energy" to 95f, "danceability" to 75f, "tempo" to 90f),
            "hip-hop" to .85f, "dance" to .90f, "rock" to .65f, related = setOf("Party", "High Energy")),
        mood("Focus", mapOf("focus" to 100f, "instrumentalness" to 80f, "energy" to 25f, "tempo" to 20f, "atmosphere" to 60f),
            "lofi" to .90f, "instrumental" to .85f, "jazz" to .65f, "ambient" to .80f, "classical" to .45f, related = setOf("Study", "Coding", "Relax")),
        mood("Study", mapOf("focus" to 100f, "instrumentalness" to 85f, "energy" to 20f, "tempo" to 18f, "atmosphere" to 65f),
            "lofi" to .85f, "classical" to .80f, "instrumental" to .90f, "piano" to .75f, "ambient" to .60f, related = setOf("Focus", "Coding")),
        mood("Relax", mapOf("energy" to 28f, "tempo" to 30f, "atmosphere" to 82f, "focus" to 68f),
            "ambient" to .80f, "acoustic" to .75f, "lofi" to .70f, "jazz" to .55f, related = setOf("Focus", "Sleep")),
        mood("Sleep", mapOf("energy" to 10f, "tempo" to 12f, "atmosphere" to 90f, "focus" to 85f, "instrumentalness" to 85f),
            "ambient" to .90f, "classical" to .75f, "instrumental" to .85f, related = setOf("Relax", "Focus")),
        mood("Road Trip", mapOf("energy" to 70f, "tempo" to 65f, "discovery" to 85f, "familiarity" to 60f, "driving" to 100f),
            "pop" to .80f, "rock" to .70f, "indie" to .65f, "hip-hop" to .45f, "country" to .40f, related = setOf("Commute", "Night Drive")),
        mood("Commute", mapOf("energy" to 55f, "tempo" to 60f, "familiarity" to 80f, "driving" to 85f),
            "pop" to .75f, "alternative" to .60f, "electronic" to .55f, related = setOf("Road Trip", "Happy")),
        mood("Gaming", mapOf("energy" to 75f, "tempo" to 75f, "atmosphere" to 70f, "danceability" to 65f),
            "electronic" to .85f, "soundtrack" to .80f, "hip-hop" to .55f, "rock" to .45f, related = setOf("Workout", "Night Drive")),
        mood("Party", mapOf("energy" to 90f, "tempo" to 90f, "danceability" to 95f),
            "dance" to .90f, "pop" to .80f, "hip-hop" to .65f, related = setOf("Workout", "Happy")),
        mood("Happy", mapOf("energy" to 76f, "tempo" to 72f, "danceability" to 65f),
            "pop" to .85f, "dance" to .60f, "r&b" to .55f, related = setOf("Party", "Commute")),
        mood("Romantic", mapOf("energy" to 38f, "tempo" to 42f, "atmosphere" to 65f),
            "r&b" to .85f, "jazz" to .60f, "pop" to .55f, "acoustic" to .45f, related = setOf("Relax", "Sad")),
        mood("Sad", mapOf("energy" to 25f, "tempo" to 35f, "atmosphere" to 62f),
            "acoustic" to .70f, "indie" to .70f, "r&b" to .60f, "alternative" to .65f, related = setOf("Romantic", "Relax")),
        mood("Rain Drive", mapOf("energy" to 42f, "tempo" to 42f, "atmosphere" to 86f, "darkness" to 64f, "driving" to 75f),
            "alternative" to .75f, "indie" to .70f, "ambient" to .65f, "electronic" to .55f, related = setOf("Night Drive", "Relax")),
        mood("Coffee Vibes", mapOf("energy" to 40f, "tempo" to 46f, "focus" to 66f, "atmosphere" to 72f),
            "jazz" to .75f, "lofi" to .70f, "acoustic" to .55f, "indie" to .50f, related = setOf("Focus", "Relax"))
    ).associateBy { it.name.lowercase(Locale.ROOT) }

    fun profile(name: String, fallbackGenres: Set<String> = emptySet()): MoodFeatureProfile {
        val canonical = canonical(name)
        return profiles[canonical.lowercase(Locale.ROOT)] ?: MoodFeatureProfile(
            canonical, mapOf("energy" to 50f, "tempo" to 50f),
            fallbackGenres.associate { it.lowercase(Locale.ROOT) to .65f }
        )
    }

    /** Stage 1: retrieve candidates from mood-compatible genres and personal taste signals. */
    fun candidateSeeds(
        mood: String,
        favoriteGenres: Collection<String> = emptyList(),
        preferredArtists: Collection<String> = emptyList(),
        preferredLanguages: Collection<String> = emptyList(),
        limit: Int = 5
    ): List<MoodCandidateSeed> {
        val target = profile(mood, favoriteGenres.toSet())
        val favorites = favoriteGenres.map(String::trim).filter(String::isNotBlank)
        val moodGenres = target.genres.entries.sortedByDescending { it.value }.map { it.key }
        val genres = (favorites.filter { fav -> moodGenres.any { it.equals(fav, true) } } + moodGenres + favorites)
            .distinctBy { it.lowercase(Locale.ROOT) }.take(5)
        val artists = preferredArtists.map(String::trim).filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }.take(2)
        val languages = preferredLanguages.filter(String::isNotBlank).distinctBy { it.lowercase(Locale.ROOT) }.take(2)
        val genreSeeds = buildList {
            genres.take(4).forEachIndexed { index, genre ->
                val language = languages.getOrNull(index % languages.size.coerceAtLeast(1)).orEmpty()
                if (language.isNotBlank()) {
                    add(MoodCandidateSeed("$language $genre songs", setOf(genre), language = language))
                }
                add(MoodCandidateSeed("$genre songs", setOf(genre)))
            }
        }
        val artistSeeds = artists.mapIndexed { index, artist ->
            val genre = moodGenres.getOrNull(index % moodGenres.size.coerceAtLeast(1)).orEmpty()
            val language = languages.getOrNull(index % languages.size.coerceAtLeast(1)).orEmpty()
            val query = listOf(language, artist, genre, "songs").filter(String::isNotBlank).joinToString(" ")
            MoodCandidateSeed(query, setOf(genre).filter(String::isNotBlank).toSet(), setOf(artist), language)
        }
        // Interleave genre and artist retrieval so the pool includes both mood fit and known taste.
        return (genreSeeds.take(1) + artistSeeds.take(1) + genreSeeds.drop(1).take(2) + artistSeeds.drop(1) + genreSeeds.drop(3))
            .filter { it.query.isNotBlank() }.distinctBy { it.query.lowercase(Locale.ROOT) }
            .take(limit.coerceIn(1, 8))
            .ifEmpty { listOf(MoodCandidateSeed("popular songs")) }
    }

    /** Compatibility helper for callers that only need the retrieval queries. */
    fun candidateQueries(
        mood: String,
        favoriteGenres: Collection<String> = emptyList(),
        preferredArtists: Collection<String> = emptyList(),
        preferredLanguages: Collection<String> = emptyList(),
        limit: Int = 5
    ): List<String> = candidateSeeds(mood, favoriteGenres, preferredArtists, preferredLanguages, limit).map { it.query }

    fun rank(
        candidates: List<TrackFeatureProfile>, mood: String, user: CompanionProfile,
        events: List<ListeningEvent>, limit: Int = candidates.size,
        context: MoodSessionContext = MoodSessionContext(), nowMs: Long = System.currentTimeMillis(),
        retrievalGenresByTrack: Map<String, Set<String>> = emptyMap()
    ): List<String> {
        val target = MoodContextAnalyzer.adjust(profile(mood, user.favoriteGenres.toSet()), context)
        val eventHistory = events.groupBy { it.trackId }
        val artistTaste = mutableMapOf<String, Float>()
        events.asSequence().filter { it.artist.isNotBlank() }.forEach { event ->
            val ageDays = ((nowMs - event.atMs).coerceAtLeast(0L) / DAY_MS).toFloat()
            val recency = (1f - ageDays / 180f).coerceIn(.05f, 1f)
            val weight = when (event.action) {
                "like" -> 18f; "complete" -> 8f; "replay" -> 7f
                "listen" -> (event.listenedMs / 30_000f).coerceIn(0f, 4f)
                "skip" -> -12f; "unlike" -> -15f; else -> 0f
            } * recency
            val key = event.artist.normalize()
            artistTaste[key] = (artistTaste[key] ?: 0f) + weight
        }
        val ranked = candidates.asSequence()
            .filterNot { it.isCollection || it.trackId in user.excludedTrackIds }
            .mapNotNull { track ->
                val attrFit = featureFit(track.attributes, target.attributes)
                val retrievalGenres = retrievalGenresByTrack[track.trackId].orEmpty()
                    .associateWith { 0.58f }
                val candidateGenres = track.genres + retrievalGenres.filterKeys { it !in track.genres }
                val genreFit = weightedGenreFit(candidateGenres, target.genres)
                val similarity = when {
                    candidateGenres.isEmpty() && attrFit == null -> 0f
                    attrFit == null -> genreFit
                    candidateGenres.isEmpty() -> attrFit
                    else -> attrFit * .62f + genreFit * .38f
                }
                val languageFit = if (user.favoriteLanguages.isEmpty()) 0f else track.languages.entries
                    .filter { (lang, _) -> user.favoriteLanguages.any { it.equals(lang, true) } }
                    .maxOfOrNull { it.value } ?: 0f
                val liked = track.trackId in user.likedTrackIds
                val downloaded = track.trackId in user.downloadedTrackIds
                val followed = user.followedArtists.any { matchesArtist(track.artistKey, it.normalize()) }
                val artistAffinity = artistTaste.entries.filter { matchesArtist(track.artistKey, it.key) }
                    .maxOfOrNull { it.value } ?: 0f
                val history = eventHistory[track.trackId].orEmpty()
                val hasPersonalSignal = liked || followed || artistAffinity > 0f || history.any {
                    it.action in setOf("complete", "replay", "listen", "like")
                }
                val moodRelevant = similarity >= .25f || genreFit >= .26f ||
                    (hasPersonalSignal && (similarity >= .10f || genreFit >= .12f))
                if (!moodRelevant) return@mapNotNull null
                val recentSkip = history.any { it.action == "skip" && nowMs - it.atMs < 45L * DAY_MS }
                val discovery = track.attributes["discovery"] ?: 50f
                var score = similarity * 48f + genreFit * 22f + languageFit * 7f
                if (liked) score += 16f
                if (downloaded) score += 4f
                if (followed) score += 14f
                score += artistAffinity.coerceIn(-18f, 20f)
                score += track.completionRate * 7f + track.replayRate * 4f
                score += if ((target.attributes["discovery"] ?: 0f) >= 70f) discovery * .08f else (100f - discovery) * .035f
                score -= track.skipRate * 16f
                if (recentSkip) score -= 24f
                Ranked(track, score, similarity, genreFit)
            }.sortedByDescending { it.score }.toList()
        return selectDiverse(ranked, limit.coerceIn(0, 500)).map { it.track.trackId }
    }

    private data class Ranked(val track: TrackFeatureProfile, val score: Float, val similarity: Float, val genreFit: Float)

    /** Keeps approximate 70/20/10 relevance variety and caps repeated artists/genres when alternatives exist. */
    private fun selectDiverse(ranked: List<Ranked>, limit: Int): List<Ranked> {
        if (limit == 0) return emptyList()
        val core = ranked.filter { it.similarity >= .60f || it.genreFit >= .72f }.toMutableList()
        val related = ranked.filter { it !in core && (it.similarity >= .35f || it.genreFit >= .30f) }.toMutableList()
        val discovery = ranked.filter { it !in core && it !in related }.toMutableList()
        val result = mutableListOf<Ranked>()
        val artists = mutableMapOf<String, Int>()
        val genres = mutableMapOf<String, Int>()
        val coreGoal = (limit * 70 + 99) / 100
        val relatedGoal = coreGoal + (limit * 20 + 99) / 100
        fun fill(bucket: MutableList<Ranked>, goal: Int) {
            while (bucket.isNotEmpty() && result.size < minOf(limit, goal)) {
                val index = bucket.indexOfFirst { candidate ->
                    val artist = candidate.track.artistKey
                    val genre = candidate.track.genres.maxByOrNull { it.value }?.key.orEmpty()
                    (artist.isBlank() || (artists[artist] ?: 0) < 2) &&
                        (genre.isBlank() || (genres[genre] ?: 0) < maxOf(1, ((result.size + 1) * 4) / 5))
                }
                if (index < 0) break
                val chosen = bucket.removeAt(index)
                if (chosen.track.artistKey.isNotBlank()) artists[chosen.track.artistKey] = (artists[chosen.track.artistKey] ?: 0) + 1
                chosen.track.genres.maxByOrNull { it.value }?.key?.let { genres[it] = (genres[it] ?: 0) + 1 }
                result += chosen
            }
        }
        fill(core, coreGoal)
        fill(related, relatedGoal)
        fill(discovery, limit)
        // Backfill while still honoring diversity first; relax caps only if the pool is too small.
        if (result.size < limit) {
            ranked.filterNot { candidate -> result.any { it.track.trackId == candidate.track.trackId } }
                .forEach { candidate -> if (result.size < limit) result += candidate }
        }
        return result.take(limit)
    }

    private fun featureFit(actual: Map<String, Float>, target: Map<String, Float>): Float? {
        val shared = target.mapNotNull { (name, wanted) -> actual[name]?.let { abs(wanted - it) / 100f } }
        return if (shared.isEmpty()) null else (1f - shared.average().toFloat()).coerceIn(0f, 1f)
    }

    private fun weightedGenreFit(actual: Map<String, Float>, target: Map<String, Float>): Float {
        if (target.isEmpty()) return 0f
        val matches = target.entries.mapNotNull { (name, weight) ->
            actual.entries.firstOrNull { it.key.equals(name, true) }?.let { weight to it.value.coerceIn(0f, 1f) }
        }
        if (matches.isEmpty()) return 0f
        val matchedWeight = matches.sumOf { it.first.toDouble() }
        val totalWeight = target.values.sum().toDouble().coerceAtLeast(0.01)
        val confidence = matches.map { it.second }.average().toFloat()
        val coverage = (matchedWeight / totalWeight).toFloat().coerceIn(0f, 1f)
        // Missing catalog tags should not erase a valid genre match, while low-confidence
        // retrieval seeds still score below provider metadata and learned listening signals.
        return (confidence * (0.65f + 0.35f * coverage)).coerceIn(0f, 1f)
    }

    private fun canonical(name: String) = when (name.trim().lowercase(Locale.ROOT)) {
        "study", "deep work", "coding", "reading" -> "Study"
        "chill", "calm", "rainy day" -> "Relax"
        "hype mode", "gym", "running", "beast mode" -> "Workout"
        "night drive", "midnight cruise", "sunrise drive", "summer drive", "mountain drive", "coastal drive", "city cruise" -> "Night Drive"
        "road trip" -> "Road Trip"
        else -> name.trim().ifBlank { "Discover" }
    }

    private fun matchesArtist(a: String, b: String) = a.isNotBlank() && b.isNotBlank() &&
        (a == b || (minOf(a.length, b.length) >= 3 && (a.contains(b) || b.contains(a))))
    private fun String.normalize() = lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private const val DAY_MS = 24L * 60L * 60L * 1000L
}

private fun String.normalize(): String = lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

