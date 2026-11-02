package com.example.kilo.voice.domain

sealed interface VoiceResult {
    data class Parsed(val command: VoiceCommand) : VoiceResult
    data class Rejected(val message: String) : VoiceResult
}
