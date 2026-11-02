package com.example.kilo.voice.discovery

import com.example.kilo.MainViewModel
import com.example.kilo.ScreenNav
import com.example.kilo.voice.domain.VoiceCommand
import com.example.kilo.voice.domain.VoiceIntent

class DiscoveryVoiceController(private val viewModel: MainViewModel) {
    fun execute(command: VoiceCommand): String? {
        val state = viewModel.state.value
        val query = command.entity.orEmpty()
        val shelf = when (command.intent) {
            VoiceIntent.PLAY_NEW_MUSIC -> state.homeShelves.firstOrNull { it.title.contains("new", true) || it.title.contains("fresh", true) }
            VoiceIntent.PLAY_HIDDEN_GEMS -> state.homeShelves.firstOrNull { it.title.contains("hidden gem", true) || it.title.contains("underground", true) }
            VoiceIntent.PLAY_DISCOVERY -> state.homeShelves.firstOrNull { it.title.contains("discover", true) || it.title.contains("for you", true) }
            VoiceIntent.PLAY_SIMILAR -> state.homeShelves.firstOrNull { shelf -> shelf.songs.any { it.title.contains(query, true) || it.artist.contains(query, true) } }
            VoiceIntent.SURPRISE_ME -> state.homeShelves.filter { it.songs.isNotEmpty() }.randomOrNull()
            else -> null
        }
        val songs = shelf?.songs.orEmpty().filter { it.videoId.isNotBlank() || it.isDownloaded }
        if (songs.isNotEmpty()) {
            val selected = if (command.intent == VoiceIntent.SURPRISE_ME) songs.random() else songs.first()
            viewModel.playSongFromShelf(selected, songs, "voice_discovery")
            return "Playing ${shelf?.title ?: "a discovery pick"}."
        }
        if (command.intent == VoiceIntent.PLAY_SIMILAR && query.isNotBlank()) {
            viewModel.playFromAssistantQuery("songs like $query")
            return "Finding songs like $query."
        }
        viewModel.setScreen(if (command.intent == VoiceIntent.PLAY_NEW_MUSIC || command.intent == VoiceIntent.PLAY_HIDDEN_GEMS || command.intent == VoiceIntent.PLAY_DISCOVERY) ScreenNav.EXPLORE else ScreenNav.HOME)
        viewModel.reloadHome()
        return "Refreshing your music picks."
    }

    fun buildRadio(query: String? = null): String {
        val state = viewModel.state.value
        val stations = (state.radioFavorites + state.customRadioStations + listOfNotNull(state.lastRadioStation))
            .distinctBy { it.id }
        val station = if (query.isNullOrBlank()) {
            state.lastRadioStation ?: state.radioFavorites.firstOrNull() ?: state.customRadioStations.firstOrNull()
        } else {
            stations.firstOrNull { it.name.equals(query, true) }
                ?: stations.singleOrNull { it.name.contains(query, true) || query.contains(it.name, true) }
        }
        if (station != null) {
            viewModel.playRadioStation(station)
            return "Starting ${station.name}."
        }
        if (!query.isNullOrBlank()) {
            viewModel.setScreen(ScreenNav.RADIO)
            return "I couldn’t find a saved station called $query. Opening Radio."
        }
        viewModel.setScreen(ScreenNav.RADIO)
        return "Opening Radio. Choose a station to start listening."
    }
}
