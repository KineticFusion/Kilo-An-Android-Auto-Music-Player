package com.example.kilo.voice.ai

/** Optional extension point. The voice command pipeline never calls this interface. */
interface AIProvider {
    val providerName: String
    suspend fun interpret(transcript: String): String?
}
