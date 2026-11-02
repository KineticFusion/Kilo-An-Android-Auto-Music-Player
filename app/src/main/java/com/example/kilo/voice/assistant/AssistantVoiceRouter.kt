package com.example.kilo.voice.assistant

import android.content.Context
import android.os.SystemClock
import com.example.kilo.MainViewModel
import com.example.kilo.ScreenNav
import com.example.kilo.voice.domain.VoiceExecution
import com.example.kilo.voice.domain.VoiceIntent
import com.example.kilo.voice.intent.IntentParser
import com.example.kilo.voice.routing.VoiceCommandRouter

/** Assistant wrapper; playback and repository work remain in KILO's established command path. */
class AssistantVoiceRouter(context: Context, private val viewModel: MainViewModel) {
    private val commands = VoiceCommandRouter(context.applicationContext, viewModel)
    private var lastPlaylistName: String? = null

    suspend fun route(request: AssistantRequest): VoiceExecution {
        val viewModel = this.viewModel
        val started = SystemClock.elapsedRealtime()
        val contextualCommand = expandContext(request.commandText)
        val ambiguous = AssistantPlaylistBridge.ambiguousMatches(contextualCommand, viewModel.state.value)
        if (ambiguous.isNotEmpty()) {
            val result = VoiceExecution(
                false,
                "I found a few close playlist matches: ${ambiguous.joinToString(", ")}. Say the full playlist name."
            )
            AssistantAnalytics.record(VoiceIntent.PLAY_PLAYLIST, false, SystemClock.elapsedRealtime() - started)
            return result
        }

        val commandText = AssistantIntentBridge.commandFor(
            request.copy(commandText = contextualCommand),
            viewModel.state.value
        )
        // Open these destinations through the app's normal navigation state; they do not touch playback.
        when (commandText.lowercase()) {
            "open settings" -> {
                viewModel.setScreen(ScreenNav.SETTINGS)
                AssistantAnalytics.record(VoiceIntent.OPEN_SETTINGS, true, SystemClock.elapsedRealtime() - started)
                return VoiceExecution(true, "Opening Settings.")
            }
            "open artists" -> {
                viewModel.setScreen(ScreenNav.ARTISTS)
                AssistantAnalytics.record(VoiceIntent.OPEN_ARTISTS, true, SystemClock.elapsedRealtime() - started)
                return VoiceExecution(true, "Opening Artists.")
            }
        }

        val parsed = IntentParser.parse(commandText)
        val result = commands.execute(commandText)
        if (result.success && parsed.intent in setOf(VoiceIntent.PLAY_PLAYLIST, VoiceIntent.PLAY_SAVED_PLAYLIST)) {
            lastPlaylistName = parsed.entity
        }
        AssistantAnalytics.record(parsed.intent, result.success, SystemClock.elapsedRealtime() - started)
        return result
    }

    private fun expandContext(text: String): String {
        val normalized = text.trim().lowercase()
        val playlist = lastPlaylistName?.takeIf(String::isNotBlank)
        return if (playlist != null && normalized in setOf("shuffle this", "shuffle it", "shuffle that", "mix this")) {
            "shuffle playlist $playlist"
        } else text
    }
}
