package com.example.kilo.lyrics

import com.example.kilo.lyrics.models.Lyrics
import com.example.kilo.lyrics.models.LyricsLine

fun interface LyricsTranslator {
    suspend fun translate(lines: List<LyricsLine>, targetLanguageCode: String): List<String>?
}

/** Translation providers can be added here; original synced timestamps remain authoritative. */
object TranslationManager {
    @Volatile private var translator: LyricsTranslator? = null

    fun register(provider: LyricsTranslator?) { translator = provider }

    fun isAvailable(): Boolean = translator != null

    suspend fun translatedLines(lyrics: Lyrics, languageCode: String): List<LyricsLine>? {
        if (languageCode.isBlank() || lyrics.metadata.language.equals(languageCode, ignoreCase = true)) return null
        val translated = translator?.translate(lyrics.lines, languageCode)?.takeIf { it.size == lyrics.lines.size } ?: return null
        return lyrics.lines.mapIndexed { index, line -> line.copy(content = translated[index]) }
    }

    fun hasTranslation(lyrics: Lyrics, languageCode: String): Boolean =
        languageCode.isNotBlank() && lyrics.metadata.language.equals(languageCode, ignoreCase = true)
}
