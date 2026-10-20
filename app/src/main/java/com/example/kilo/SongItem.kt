package com.example.kilo

import androidx.compose.ui.graphics.Color

data class SongItem(
    val id: String,
    val videoId: String = "",
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String,
    val audioUrl: String = "",
    val themeColor: Color = Color(0xFF8A2BE2),
    val durationMs: Long = 215000L,
    val codecInfo: String = "Opus • 48.0 kHz • 271 kbps • Stereo",
    val isDownloaded: Boolean = false,
    val isExplicit: Boolean = false
)

enum class UiSkin(val title: String, val highlight: Color) {
    LIQUID_GLASS("Liquid Glass", Color(0xFFE8F4F8)),
    SPOTIFY("Spotify", Color(0xFF1ED760)),
    BITCHORD("BitChord", Color(0xFFB388FF))
}

enum class AppScreen {
    HOME, EXPLORE, LIBRARY, SEARCH, OFFLINE, SETTINGS
}

val BITCHORD_CURATED_HITS = listOf(
    SongItem(
        id = "b_1",
        videoId = "ab9176Srb5Y",
        title = "Starboy (Master FLAC)",
        artist = "The Weeknd, Daft Punk",
        album = "Starboy",
        coverUrl = "https://i.ytimg.com/vi/34Na4j8AVgA/maxresdefault.jpg",
        themeColor = Color(0xFFDA70D6),
        durationMs = 230000L,
        codecInfo = "Opus • 48.0 kHz • 320 kbps • Stereo",
    ),
    SongItem(
        id = "b_2",
        videoId = "L_LUpnjgPso",
        title = "Murder In My Mind",
        artist = "Kordhell",
        album = "Phonk Reels",
        coverUrl = "https://i.ytimg.com/vi/L_LUpnjgPso/maxresdefault.jpg",
        themeColor = Color(0xFFFF3333),
        durationMs = 145000L,
        codecInfo = "Opus • 48.0 kHz • 320 kbps • Stereo",
    ),
    SongItem(
        id = "b_3",
        videoId = "2Vv-BfVoq4g",
        title = "Midnight City",
        artist = "M83",
        album = "Hurry Up, We're Dreaming",
        coverUrl = "https://i.ytimg.com/vi/dX3k_QDnzHE/maxresdefault.jpg",
        themeColor = Color(0xFF1E90FF),
        durationMs = 244000L,
        codecInfo = "AAC • 44.1 kHz • 256 kbps • Stereo",
    )
)
