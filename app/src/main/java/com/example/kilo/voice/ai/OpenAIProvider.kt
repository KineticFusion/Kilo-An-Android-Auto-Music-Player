package com.example.kilo.voice.ai

/** BYOK-only adapter contract. A caller must supply its own key and transport explicitly. */
class OpenAIProvider(
    private val userApiKey: String,
    private val request: suspend (apiKey: String, transcript: String) -> String?
) : AIProvider {
    override val providerName: String = "OpenAI (optional)"
    override suspend fun interpret(transcript: String): String? = userApiKey.takeIf(String::isNotBlank)?.let { request(it, transcript) }
}
