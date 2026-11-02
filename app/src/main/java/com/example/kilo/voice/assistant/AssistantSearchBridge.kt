package com.example.kilo.voice.assistant

object AssistantSearchBridge {
    fun command(query: String): String {
        val clean = query.trim()
        if (clean.isBlank()) return "open search"
        return if (clean.startsWith("search ", true) || clean.startsWith("find ", true)) clean
        else "search for $clean"
    }
}
