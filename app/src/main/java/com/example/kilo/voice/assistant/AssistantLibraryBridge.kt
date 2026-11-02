package com.example.kilo.voice.assistant

/** Normalizes short library names while preserving the shared voice router's command grammar. */
object AssistantLibraryBridge {
    fun command(query: String): String {
        val value = query.trim()
        return when (value.lowercase()) {
            "liked", "likes", "liked songs", "my likes" -> "play my liked songs"
            "downloads", "downloaded", "offline", "offline music" -> "play my downloads"
            "library", "my library", "collection" -> "open library"
            "favourites", "favorites", "my favourites", "my favorites" -> "play my favourites"
            else -> value
        }
    }
}
