package com.example.kilo.voice.assistant

object AssistantRadioBridge {
    fun command(query: String): String {
        val value = query.trim()
        return when (value.lowercase()) {
            "radio", "my radio", "start radio" -> "start radio"
            else -> value
        }
    }
}
