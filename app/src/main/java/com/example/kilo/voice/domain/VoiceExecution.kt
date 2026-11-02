package com.example.kilo.voice.domain

data class VoiceExecution(
    val success: Boolean,
    val message: String,
    val requiresConfirmation: Boolean = false,
    val command: VoiceCommand? = null
)
