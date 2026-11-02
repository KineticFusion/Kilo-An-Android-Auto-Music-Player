package com.example.kilo.voice.assistant

object AssistantDiscoveryBridge {
    fun command(query: String): String {
        val value = query.trim()
        return when (value.lowercase()) {
            "something new", "new music", "discover something", "discover music" -> "play something new"
            "hidden gem", "hidden gems" -> "play hidden gems"
            "surprise", "surprise me", "pick for me" -> "surprise me"
            else -> value
        }
    }
}
