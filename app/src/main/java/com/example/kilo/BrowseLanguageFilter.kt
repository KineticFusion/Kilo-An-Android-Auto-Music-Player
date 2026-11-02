package com.example.kilo

import java.util.Locale

/**
 * Keeps explicit language browse pages from mixing confidently identified languages.
 * Search results do not currently expose a dependable language field, so this uses
 * track metadata, language labels and Unicode script evidence. Unclassified titles are
 * omitted from a language-specific page instead of being guessed into that language.
 */
internal object BrowseLanguageFilter {
    private val nativeSearchPhrases = mapOf(
        "Assamese" to "অসমীয়া গান",
        "Bengali" to "বাংলা গান",
        "Gujarati" to "ગુજરાતી ગીતો",
        "Hindi" to "हिंदी गाने",
        "Kannada" to "ಕನ್ನಡ ಹಾಡುಗಳು",
        "Malayalam" to "മലയാളം പാട്ടുകൾ",
        "Marathi" to "मराठी गाणी",
        "Nepali" to "नेपाली गीत",
        "Odia" to "ଓଡ଼ିଆ ଗୀତ",
        "Punjabi" to "ਪੰਜਾਬੀ ਗਾਣੇ",
        "Sanskrit" to "संस्कृत गीत",
        "Tamil" to "தமிழ் பாடல்கள்",
        "Telugu" to "తెలుగు పాటలు",
        "Urdu" to "اردو گانے",
        "Chinese" to "中文歌曲",
        "Japanese" to "日本語の歌",
        "Korean" to "한국 노래",
        "Arabic" to "أغاني عربية",
        "Russian" to "русские песни",
        "Thai" to "เพลงไทย"
    )
    private val canonicalNames = mapOf(
        "assamese" to "Assamese", "bangla" to "Bengali", "bengali" to "Bengali",
        "chinese" to "Chinese", "mandarin" to "Chinese", "english" to "English",
        "filipino" to "Filipino", "tagalog" to "Filipino", "french" to "French",
        "german" to "German", "greek" to "Greek", "gujarati" to "Gujarati",
        "hebrew" to "Hebrew", "hindi" to "Hindi", "indonesian" to "Indonesian",
        "italian" to "Italian", "japanese" to "Japanese", "kannada" to "Kannada",
        "korean" to "Korean", "malayalam" to "Malayalam", "marathi" to "Marathi",
        "nepali" to "Nepali", "nigerian pidgin" to "Nigerian Pidgin", "dutch" to "Dutch",
        "odia" to "Odia", "oriya" to "Odia", "persian" to "Persian", "farsi" to "Persian",
        "polish" to "Polish", "portuguese" to "Portuguese", "punjabi" to "Punjabi",
        "urdu" to "Urdu", "russian" to "Russian", "sanskrit" to "Sanskrit",
        "sinhala" to "Sinhala", "spanish" to "Spanish", "swahili" to "Swahili",
        "tamil" to "Tamil", "telugu" to "Telugu", "thai" to "Thai", "turkish" to "Turkish",
        "ukrainian" to "Ukrainian", "vietnamese" to "Vietnamese", "yoruba" to "Yoruba"
    )
    private val languageCodes = mapOf(
        "ar" to "Arabic", "as" to "Assamese", "bn" to "Bengali", "de" to "German",
        "el" to "Greek", "en" to "English", "es" to "Spanish", "fa" to "Persian",
        "fil" to "Filipino", "fr" to "French", "gu" to "Gujarati", "he" to "Hebrew",
        "hi" to "Hindi", "id" to "Indonesian", "it" to "Italian", "ja" to "Japanese",
        "kn" to "Kannada", "ko" to "Korean", "ml" to "Malayalam", "mr" to "Marathi",
        "ne" to "Nepali", "nl" to "Dutch", "or" to "Odia", "pa" to "Punjabi",
        "pl" to "Polish", "pt" to "Portuguese", "ru" to "Russian", "sa" to "Sanskrit",
        "si" to "Sinhala", "sw" to "Swahili", "ta" to "Tamil", "te" to "Telugu",
        "th" to "Thai", "tr" to "Turkish", "uk" to "Ukrainian", "ur" to "Urdu",
        "vi" to "Vietnamese", "yo" to "Yoruba", "zh" to "Chinese"
    )

    private fun key(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun canonicalLanguage(value: String): String? {
        val normalized = key(value)
        canonicalNames[normalized]?.let { return it }
        canonicalNames.values.firstOrNull { it.equals(value.trim(), ignoreCase = true) }?.let { return it }
        val code = value.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        return languageCodes[code]
    }

    fun isLanguage(value: String): Boolean = canonicalLanguage(value) != null

    /** Prefer native-script catalog queries where transliterated titles are common. */
    fun discoveryQueries(value: String): List<String> {
        val language = canonicalLanguage(value) ?: return emptyList()
        val native = nativeSearchPhrases[language]
        return buildList {
            native?.let(::add)
            add("$language songs")
            add("$language hits songs")
        }.distinct().take(3)
    }

    /** Only return results with positive language evidence; do not let ranking re-add others. */
    fun filter(songs: List<SongItemClean>, requestedLanguage: String): List<SongItemClean> {
        val requested = canonicalLanguage(requestedLanguage) ?: return emptyList()
        return songs.filter { matches(it, requested) }
    }

    /**
     * Search providers often return romanized titles for native-script language queries
     * without attaching a language code. Keep explicit language metadata authoritative,
     * but use the native-language query as evidence when the provider leaves it unset.
     */
    fun filterFromSearch(songs: List<SongItemClean>, requestedLanguage: String, query: String): List<SongItemClean> {
        val requested = canonicalLanguage(requestedLanguage) ?: return emptyList()
        val nativePhrase = nativeSearchPhrases[requested] ?: return filter(songs, requested)
        val nativeQuery = query.contains(nativePhrase, ignoreCase = true)
        return songs.filter { song ->
            matches(song, requested) || (nativeQuery && hasNoConflictingLanguage(song, requested))
        }
    }

    private fun hasNoConflictingLanguage(song: SongItemClean, requested: String): Boolean {
        canonicalLanguage(song.languageCode)?.let { return it == requested }
        explicitLanguage("${song.title} ${song.album}")?.let { return it == requested }
        return true
    }

    fun matches(song: SongItemClean, requestedLanguage: String): Boolean {
        val requested = canonicalLanguage(requestedLanguage) ?: return false
        canonicalLanguage(song.languageCode)?.let { return it == requested }
        val titleMetadata = "${song.title} ${song.album}"

        // Accept a title/album label only when it explicitly identifies the recording's
        // language; artist names are deliberately excluded as evidence.
        explicitLanguage(titleMetadata)?.let { return it == requested }

        // Artist names do not identify the language of a recording. For example, a
        // Kannada artist can release an English song, so only inspect song/album text.
        val scripts = scriptCounts(titleMetadata)
        if (scripts.isNotEmpty()) {
            // A track may have a Latin transliteration beside its native script, but a
            // second recognized script is a strong signal that this result is unrelated.
            val winner = scripts.maxByOrNull { it.value }?.key ?: return false
            // Devanagari is shared by Hindi, Marathi, Nepali and Sanskrit. Without an
            // explicit provider language or a language label, assigning it to Hindi
            // would leak other languages into a language-only browse page.
            if (winner == "Devanagari") return false
            if (winner != requested) return false
            val competing = scripts.filterKeys { it != winner }.values.sum()
            return scripts.getValue(winner) >= competing
        }

        // A few languages have characters not used by their close script peers.
        val chars = titleMetadata.lowercase(Locale.ROOT)
        return when (requested) {
            "German" -> 'ß' in chars || chars.any { it in "äöü" }
            "Turkish" -> chars.any { it in "ıİşğç" }
            "Vietnamese" -> chars.any { it in "ăâđêôơư" } || hasVietnameseToneMarks(titleMetadata)
            "Polish" -> chars.any { it in "łńśźżćąę" }
            "Spanish" -> chars.any { it in "ñ¿¡" }
            "Portuguese" -> chars.any { it in "ãõ" }
            "Yoruba" -> chars.any { it in "ẹọṣ" }
            "Ukrainian" -> chars.any { it in "їєґі" }
            // Romanized words without language metadata are inherently ambiguous
            // (for example Hindi, Kannada and Tamil can all be written in Latin script).
            else -> false
        }
    }

    private fun explicitLanguage(text: String): String? {
        val normalized = " ${key(text)} "
        return canonicalNames.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (alias, _) -> " $alias " in normalized }
            ?.value
    }

    private fun scriptCounts(text: String): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        text.codePoints().forEach { cp ->
            val language = when (cp) {
                in 0x0B80..0x0BFF -> "Tamil"
                in 0x0C00..0x0C7F -> "Telugu"
                in 0x0C80..0x0CFF -> "Kannada"
                in 0x0D00..0x0D7F -> "Malayalam"
                in 0x0980..0x09FF -> "Bengali"
                in 0x0A00..0x0A7F -> "Punjabi"
                in 0x0A80..0x0AFF -> "Gujarati"
                in 0x0B00..0x0B7F -> "Odia"
                in 0x0D80..0x0DFF -> "Sinhala"
                in 0x0E00..0x0E7F -> "Thai"
                in 0x1100..0x11FF, in 0xAC00..0xD7AF -> "Korean"
                in 0x3040..0x30FF -> "Japanese"
                in 0x4E00..0x9FFF -> "Chinese"
                in 0x0600..0x06FF -> when (cp) {
                    in 0x067E..0x067E, in 0x0686..0x0686, in 0x0698..0x0698, in 0x06AF..0x06AF -> "Persian"
                    in 0x0679..0x0679, in 0x0688..0x0688, in 0x0691..0x0691,
                    in 0x06BA..0x06BA, in 0x06BE..0x06BE, in 0x06D2..0x06D3 -> "Urdu"
                    else -> "Arabic"
                }
                in 0x0900..0x097F -> "Devanagari"
                in 0x0370..0x03FF -> "Greek"
                in 0x0590..0x05FF -> "Hebrew"
                in 0x0400..0x052F -> when (cp) {
                    in 0x0404..0x0404, in 0x0456..0x0457, in 0x0490..0x0491 -> "Ukrainian"
                    else -> "Russian"
                }
                else -> null
            }
            if (language != null) counts[language] = (counts[language] ?: 0) + 1
        }
        return counts
    }

    private fun hasVietnameseToneMarks(text: String): Boolean = text.any { ch ->
        ch.code in 0x1EA0..0x1EF9
    }
}
