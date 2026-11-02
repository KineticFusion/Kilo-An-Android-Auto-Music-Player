package com.example.kilo.voice.ai

/** No provider is configured by default; core voice remains local and deterministic. */
object AIProviderManager {
    @Volatile var provider: AIProvider? = null
        private set

    fun enableWithUserProvider(value: AIProvider?) { provider = value }
    fun isEnabled(): Boolean = provider != null
}
