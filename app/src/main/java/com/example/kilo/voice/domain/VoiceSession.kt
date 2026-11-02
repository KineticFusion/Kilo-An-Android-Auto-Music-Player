package com.example.kilo.voice.domain

data class VoiceSession(
    val phase: Phase = Phase.IDLE,
    val transcript: String = "",
    val partialTranscript: String = "",
    val lastExecution: VoiceExecution? = null
) {
    enum class Phase { IDLE, LISTENING, PROCESSING, CONFIRMING, COMPLETE, ERROR }
}
