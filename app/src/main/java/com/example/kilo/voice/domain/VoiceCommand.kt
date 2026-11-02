package com.example.kilo.voice.domain

data class VoiceCommand(
    val transcript: String,
    val intent: VoiceIntent,
    val entity: String? = null,
    val secondaryEntity: String? = null,
    val amountMs: Long? = null,
    val enabled: Boolean? = null,
    val confidence: Float = 1f
)
