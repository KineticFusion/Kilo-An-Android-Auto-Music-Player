package com.example.kilo.voice.domain

sealed interface VoiceAction {
    data class Playback(val intent: VoiceIntent, val amountMs: Long? = null, val enabled: Boolean? = null) : VoiceAction
    data class Library(val intent: VoiceIntent, val query: String? = null) : VoiceAction
    data class Playlist(val intent: VoiceIntent, val name: String, val newName: String? = null) : VoiceAction
    data class Queue(val intent: VoiceIntent, val query: String? = null) : VoiceAction
    data class Discovery(val intent: VoiceIntent, val query: String? = null) : VoiceAction
    data class Search(val query: String) : VoiceAction
    data class Navigate(val destination: VoiceIntent) : VoiceAction
    data object Unrecognized : VoiceAction
}
