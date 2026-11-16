package com.example.kilo.streaming

enum class SourceType {
    YOUTUBE,
    SPOTIFY,
    ADDON_MANIFEST,
    LOCAL
}

/**
 * Persistent configuration for a single modular source addon (YouTube, Spotify, BitChord addons, etc.).
 */
data class ConfiguredSource(
    val id: String,
    val name: String,
    val type: SourceType,
    val baseUrl: String = "",
    val priority: Int = 100,
    val enabled: Boolean = true,
    val supportsLossless: Boolean = false,
    val version: String = "1.0.0",
    val description: String = "",
    val statusMessage: String = "Ready"
)

/**
 * Lightweight, module-agnostic track model returned by any AudioSourceProvider.
 * Maps cleanly to app-level SongItem / SongItemClean without circular dependencies.
 */
data class HybridTrack(
    val canonicalId: String,      // e.g., "yt:dQw4w9WgXcQ", "spotify:4uLU6hMCjMI75M1A2tKUQC", or "addon_xyz:10492"
    val rawId: String,            // Source-native ID
    val sourceId: String,         // Matches ConfiguredSource.id
    val title: String,
    val artist: String,
    val album: String = "",
    val coverUrl: String = "",
    val durationMs: Long = 0L,
    val codecBadge: String = "",
    val isLossless: Boolean = false,
    val isExplicit: Boolean = false
) {
    companion object {
        fun parsePrefix(id: String): Pair<String, String> {
            val idx = id.indexOf(':')
            return if (idx > 0) {
                id.substring(0, idx) to id.substring(idx + 1)
            } else {
                "yt" to id
            }
        }
    }
}

/**
 * Universal plugin contract for any streaming or catalog addon in Kilo.
 */
interface AudioSourceProvider {
    val config: ConfiguredSource

    suspend fun search(query: String, limit: Int = 20): List<HybridTrack>

    /**
     * Resolves a playable stream lease.
     * Matches either by source-native track ID OR metadata (title + artist + durationMs) for cross-source fallback.
     */
    suspend fun resolveStream(
        trackId: String,
        title: String = "",
        artist: String = "",
        durationMs: Long = 0L,
        preferLossless: Boolean = true
    ): ResolvedStream?

    fun invalidateTrack(trackId: String) {}
}