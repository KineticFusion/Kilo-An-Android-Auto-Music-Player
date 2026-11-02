package com.example.kilo.voice.ai

/** BYOK-only adapter contract. A caller must supply its own key and transport explicitly. */
class GeminiProvider(
    private val userApiKey: String,
    private val request: suspend (apiKey: String, transcript: String) -> String?
) : AIProvider {
    override val providerName: String = "Gemini (optional)"
    override suspend fun interpret(transcript: String): String? = userApiKey.takeIf(String::isNotBlank)?.let { request(it, transcript) }
}
