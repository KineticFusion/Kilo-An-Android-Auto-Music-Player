package com.example.kilo.voice.assistant

import android.app.SearchManager
import android.content.Intent
import com.example.kilo.AppStateModel
import com.example.kilo.voice.domain.VoiceIntent
import com.example.kilo.voice.intent.IntentParser

data class AssistantRequest(
    val commandText: String,
    val sourceAction: String
)

/** Converts App Actions and Android media-search intents into KILO's local command language. */
object AssistantIntentBridge {
    fun fromIntent(intent: Intent?): AssistantRequest? {
        intent ?: return null
        val action = intent.action.orEmpty()
        val query = firstText(
            intent,
            AssistantCapabilityProvider.EXTRA_SONG_QUERY,
            "audio.musicSearch.query",
            AssistantCapabilityProvider.EXTRA_QUERY,
            SearchManager.QUERY,
            Intent.EXTRA_TEXT,
            "androidx.media3.session.QUERY"
        ).orEmpty()
        val hasAudioSearchQuery = firstText(
            intent,
            AssistantCapabilityProvider.EXTRA_SONG_QUERY,
            "audio.musicSearch.query"
        )?.isNotBlank() == true
        val playlistName = firstText(intent, "playlistName").orEmpty()
        val mediaQuery = mediaQuery(intent, query).ifBlank { query }

        val command = when {
            playlistName.isNotBlank() || action == AssistantCapabilityProvider.ACTION_PLAY_PLAYLIST ->
                AssistantPlaybackBridge.command("play playlist ${playlistName.ifBlank { query }}")
            hasAudioSearchQuery -> AssistantPlaybackBridge.command(mediaQuery)
            action == AssistantCapabilityProvider.ACTION_SEARCH ||
                action == AssistantCapabilityProvider.ACTION_ANDROID_SEARCH -> AssistantSearchBridge.command(query)
            action == AssistantCapabilityProvider.ACTION_PLAY ||
                action == AssistantCapabilityProvider.ACTION_MEDIA_PLAY_FROM_SEARCH -> AssistantPlaybackBridge.command(mediaQuery)
            action == AssistantCapabilityProvider.ACTION_OPEN_FEATURE -> openFeature(
                firstText(intent, AssistantCapabilityProvider.EXTRA_FEATURE).orEmpty()
            )
            else -> {
                if (query.isBlank()) return null
                AssistantPlaybackBridge.command(query)
            }
        }
        val routedAction = when {
            playlistName.isNotBlank() || action == AssistantCapabilityProvider.ACTION_PLAY_PLAYLIST -> AssistantCapabilityProvider.ACTION_PLAY_PLAYLIST
            hasAudioSearchQuery -> AssistantCapabilityProvider.ACTION_AUDIO_SEARCH
            else -> action
        }
        return AssistantRequest(command, routedAction)
    }

    fun commandFor(request: AssistantRequest, state: AppStateModel): String {
        val phrase = request.commandText.trim()
        if (phrase.isBlank()) return "open home"
        if (request.sourceAction == AssistantCapabilityProvider.ACTION_SEARCH ||
            request.sourceAction == AssistantCapabilityProvider.ACTION_ANDROID_SEARCH) return phrase

        AssistantPlaylistBridge.preferExactPlaylist(phrase, state)?.let { return it }
        val parsed = IntentParser.parse(phrase)
        return when (parsed.intent) {
            VoiceIntent.PLAY, VoiceIntent.PLAY_PLAYLIST, VoiceIntent.PLAY_SAVED_PLAYLIST,
            VoiceIntent.PLAY_DOWNLOADED_PLAYLIST, VoiceIntent.PLAY_LIKED_SONGS,
            VoiceIntent.PLAY_DOWNLOADS, VoiceIntent.PLAY_FAVOURITES, VoiceIntent.PLAY_LIBRARY,
            VoiceIntent.SAVE_PLAYLIST, VoiceIntent.DOWNLOAD_PLAYLIST,
            VoiceIntent.CREATE_PLAYLIST, VoiceIntent.ADD_TO_PLAYLIST,
            VoiceIntent.PLAY_RECENT_PLAYLIST -> AssistantPlaylistBridge.command(phrase)
            VoiceIntent.PLAY_NEW_MUSIC, VoiceIntent.PLAY_HIDDEN_GEMS,
            VoiceIntent.PLAY_DISCOVERY, VoiceIntent.SURPRISE_ME -> AssistantDiscoveryBridge.command(phrase)
            VoiceIntent.BUILD_RADIO, VoiceIntent.OPEN_RADIO -> AssistantRadioBridge.command(phrase)
            VoiceIntent.OPEN_LIBRARY, VoiceIntent.OPEN_DOWNLOADS -> AssistantLibraryBridge.command(phrase)
            else -> phrase
        }
    }

    private fun openFeature(feature: String): String = when (feature.trim().lowercase()) {
        "", "kilo", "home", "home page", "player" -> "open home"
        "browse", "explore", "moods", "genres", "languages", "discovery" -> "open browse"
        "search" -> "open search"
        "radio" -> "open radio"
        "library", "collection" -> "open library"
        "downloads", "offline" -> "open downloads"
        "settings", "preferences" -> "open settings"
        "artists" -> "open artists"
        else -> "open ${feature.trim()}"
    }

    private fun mediaQuery(intent: Intent, fallbackQuery: String): String {
        val title = firstText(intent, "android.intent.extra.MEDIA_TITLE").orEmpty()
        val artist = firstText(intent, "android.intent.extra.MEDIA_ARTIST").orEmpty()
        val album = firstText(intent, "android.intent.extra.MEDIA_ALBUM").orEmpty()
        val focus = firstText(intent, "android.intent.extra.MEDIA_FOCUS").orEmpty()
        val playlist = firstText(intent, "android.intent.extra.MEDIA_PLAYLIST").orEmpty()
        if (playlist.isNotBlank() || focus.contains("playlist", ignoreCase = true)) {
            val name = playlist.ifBlank { fallbackQuery }
            if (name.isNotBlank()) return "play playlist $name"
        }
        if (title.isNotBlank()) return listOf(title, artist.takeIf(String::isNotBlank)?.let { "by $it" })
            .filterNotNull().joinToString(" ")
        if (artist.isNotBlank()) return "$artist ${if (focus.contains("album", true)) "album" else "songs"}"
        if (album.isNotBlank()) return "$album album"
        return ""
    }

    private fun firstText(intent: Intent, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        runCatching { intent.extras?.get(key)?.toString()?.trim()?.takeIf(String::isNotBlank) }.getOrNull()
    }
}
