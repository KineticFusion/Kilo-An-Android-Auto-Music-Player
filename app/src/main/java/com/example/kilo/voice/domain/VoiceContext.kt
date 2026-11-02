package com.example.kilo.voice.domain

data class VoiceContext(
    val currentTitle: String = "",
    val currentArtist: String = "",
    val isPlaying: Boolean = false,
    val isSearching: Boolean = false,
    val playlistNames: List<String> = emptyList(),
    val downloadedCount: Int = 0,
    val likedCount: Int = 0
)
