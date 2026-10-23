package com.example.kilo.search

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class SearchFilterType { LANGUAGE, GENRE, MOOD, ACTIVITY }

data class SearchQuickFilter(
    val label: String,
    val queryTerm: String,
    val type: SearchFilterType
)

data class SearchIntent(
    val rawQuery: String,
    val language: String? = null,
    val mood: String? = null,
    val activity: String? = null,
    val genre: String? = null,
    val discovery: String? = null,
    val entity: String? = null
) {
    val hasEnhancement: Boolean
        get() = language != null || mood != null || activity != null || genre != null || discovery != null
}

object LanguageSearchEngine {
    private val languages = setOf(
        "afrikaans", "akan", "albanian", "amharic", "arabic", "armenian", "assamese", "azerbaijani",
        "bambara", "basque", "belarusian", "bengali", "bosnian", "bulgarian", "burmese", "cantonese",
        "catalan", "cebuano", "chinese", "croatian", "czech", "danish", "dutch", "english", "estonian",
        "filipino", "finnish", "french", "galician", "georgian", "german", "greek", "gujarati", "hausa",
        "hebrew", "hindi", "hungarian", "icelandic", "igbo", "indonesian", "irish", "italian", "japanese",
        "javanese", "kannada", "kazakh", "khmer", "korean", "kurdish", "lao", "latin", "latvian", "lingala",
        "lithuanian", "macedonian", "malay", "malayalam", "mandarin", "marathi", "mongolian", "nepali",
        "norwegian", "odia", "pashto", "persian", "polish", "portuguese", "punjabi", "romanian", "russian",
        "sanskrit", "serbian", "sindhi", "sinhala", "slovak", "slovenian", "somali", "spanish", "swahili",
        "swedish", "tagalog", "tajik", "tamil", "telugu", "thai", "tigrinya", "turkish", "ukrainian", "urdu",
        "uzbek", "vietnamese", "welsh", "wolof", "yoruba", "zulu"
    ) + Locale.getISOLanguages().map { Locale(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) }

    fun detect(query: String): String? {
        val text = query.trim()
        val parenthesized = Regex("(?i)\\(([^()]+)\\)\\s*$").find(text)
            ?.groupValues?.get(1)?.trim()?.lowercase(Locale.ROOT)
        if (parenthesized != null && parenthesized in languages) return parenthesized
        return text.split(Regex("\\s+")).firstNotNullOfOrNull { it.trim('(', ')', ',', '.', '!', '?').lowercase(Locale.ROOT).takeIf(languages::contains) }
    }

    fun supplementalQueries(query: String): List<String> {
        val language = detect(query) ?: return emptyList()
        val base = query.trim()
            .replace(Regex("(?i)\\s*\\(" + Regex.escape(language) + "\\)\\s*$"), "")
            .replace(Regex("(?i)\\b" + Regex.escape(language) + "\\b"), "")
            .trim()
        if (base.isBlank()) return emptyList()
        return listOf("$base $language song", "$base $language").distinct()
    }

    fun quickFilters() = listOf("English", "Hindi", "Tamil", "Kannada", "Telugu", "Malayalam", "Korean", "Spanish")
        .map { SearchQuickFilter(it, "($it)", SearchFilterType.LANGUAGE) }
}

object MoodSearchEngine {
    private val moods = listOf("happy", "sad", "romantic", "relax", "relaxed", "focus", "night drive", "party", "nostalgic")
    fun detect(query: String): String? = moods.firstOrNull { query.contains(it, ignoreCase = true) }
}

object IntentEngine {
    private val activities = listOf("workout", "gym", "coding", "study", "road trip", "commute", "gaming", "rainy weather", "rain")
    private val genres = listOf("hip-hop", "hip hop", "edm", "pop", "rock", "jazz", "classical", "indie", "r&b", "country")

    fun parse(query: String): SearchIntent {
        val normalized = query.trim().lowercase(Locale.ROOT)
        val entity = Regex("(?i)songs? like (.+)$|artists? like (.+)$|because you like (.+)$")
            .find(query)?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)
        return SearchIntent(
            rawQuery = query,
            language = LanguageSearchEngine.detect(query),
            mood = MoodSearchEngine.detect(normalized),
            activity = activities.firstOrNull { normalized.contains(it) },
            genre = genres.firstOrNull { normalized.contains(it) },
            discovery = DiscoverySearchEngine.detect(normalized),
            entity = entity?.trim()
        )
    }
}

object DiscoverySearchEngine {
    private val intents = listOf("hidden gems", "trending", "recommended artists", "recommended albums", "similar to", "songs like", "artists similar", "because you like")
    fun detect(query: String): String? = intents.firstOrNull { query.contains(it, ignoreCase = true) }
}

object KeywordRecommendationEngine {
    fun suggestions(query: String): List<String> {
        val intent = IntentEngine.parse(query)
        return buildList {
            intent.language?.let { add("$it songs") }
            intent.mood?.let { add("$it music") }
            intent.activity?.let { add("$it playlist") }
            intent.genre?.let { add("$it mix") }
            intent.entity?.let { add("similar to $it") }
        }.distinct()
    }
}

object SearchAnalytics {
    private val queryCounts = ConcurrentHashMap<String, Int>()
    private val queryTimes = ConcurrentHashMap<String, Long>()
    fun record(query: String) {
        val key = query.trim().lowercase(Locale.ROOT)
        if (key.isNotBlank()) {
            queryCounts.merge(key, 1, Int::plus)
            queryTimes[key] = System.currentTimeMillis()
        }
    }
    fun recentSearches(limit: Int = 5): List<String> = queryCounts.keys
        .sortedByDescending { queryTimes[it] ?: 0L }.take(limit)
}

object SearchEnhancer {
    fun intent(query: String) = IntentEngine.parse(query)
    fun supplementalQueries(query: String, currentTitles: List<String> = emptyList()): List<String> {
        val languageQueries = LanguageSearchEngine.supplementalQueries(query)
        if (languageQueries.isNotEmpty()) return languageQueries
        val intent = IntentEngine.parse(query)
        if (intent.mood != null || intent.activity != null || intent.genre != null || intent.discovery != null) return emptyList()
        val clean = query.trim()
        if (clean.length < 2) return emptyList()
        val normalized = clean.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val titleCase = clean.split(Regex("\\s+")).joinToString(" ") { token ->
            token.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        // Keep the original result set intact and search title/official-audio
        // variants in parallel as an additive layer for every ordinary query.
        return listOf("$titleCase song", "$titleCase official audio")
            .distinct()
            .filterNot { it.equals(clean, ignoreCase = true) }
    }
    fun suggestions(query: String): List<String> {
        val language = LanguageSearchEngine.detect(query)
        if (language != null) {
            val base = query.trim()
                .replace(Regex("(?i)\\s*\\(" + Regex.escape(language) + "\\)\\s*$"), "")
                .replace(Regex("(?i)\\b" + Regex.escape(language) + "\\b"), "")
                .trim()
            return if (base.isBlank()) listOf("$language songs") else listOf("$base in $language")
        }
        val intentSuggestions = KeywordRecommendationEngine.suggestions(query)
        if (intentSuggestions.isNotEmpty()) return intentSuggestions
        val clean = query.trim()
        if (clean.length < 2) return emptyList()
        val titleCase = clean.split(Regex("\\s+")).joinToString(" ") { token ->
            token.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        return listOf("$titleCase songs", "$titleCase official audio").distinct()
    }
    fun quickFilters() = LanguageSearchEngine.quickFilters() + listOf(
        SearchQuickFilter("Hip-Hop", "hip-hop", SearchFilterType.GENRE),
        SearchQuickFilter("EDM", "EDM", SearchFilterType.GENRE),
        SearchQuickFilter("Workout", "workout music", SearchFilterType.ACTIVITY),
        SearchQuickFilter("Night Drive", "night drive", SearchFilterType.MOOD),
        SearchQuickFilter("Gaming", "gaming music", SearchFilterType.ACTIVITY),
        SearchQuickFilter("Study", "study music", SearchFilterType.ACTIVITY),
        SearchQuickFilter("Road Trip", "road trip", SearchFilterType.ACTIVITY),
        SearchQuickFilter("Focus", "focus music", SearchFilterType.MOOD)
    )

    fun <T> merge(original: List<T>, supplemental: List<T>, key: (T) -> String): List<T> {
        val seen = original.mapTo(hashSetOf(), key)
        return original + supplemental.filter { seen.add(key(it)) }
    }

    fun <T> rankSupplemental(items: List<T>, preferenceTerms: List<String>, searchableText: (T) -> String): List<T> {
        if (preferenceTerms.isEmpty()) return items
        val terms = preferenceTerms.map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank).distinct()
        return items.withIndex().sortedWith(
            compareByDescending<IndexedValue<T>> { indexed ->
                val text = searchableText(indexed.value).lowercase(Locale.ROOT)
                terms.count { text.contains(it) }
            }.thenBy { it.index }
        ).map { it.value }
    }
}
