package com.example.kilo.spotify

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.math.max
import com.example.kilo.streaming.ResolvedStream
import com.example.kilo.streaming.HybridTrack
import com.example.kilo.SongItemClean

// Type alias to reference SpotifyApiClient.SpotifyTrack
typealias SpotifyApiTrack = com.example.kilo.spotify.SpotifyTrack

/**
 * Spotify-specific data models for UI
 */
data class SpotifyAccount(
    val id: String,
    val displayName: String,
    val email: String?,
    val images: List<SpotifyImage>,
    val country: String?,
    val product: String, // "free", "premium", etc.
    val followers: Long,
    val externalUrls: Map<String, String>
)

data class SpotifyCurrentlyPlaying(
    val timestamp: Long,
    val context: SpotifyPlaybackContext?,
    val progressMs: Long,
    val item: SpotifyApiTrack?,
    val currentlyPlayingType: String, // "track", "episode", "ad"
    val actions: SpotifyActions,
    val isPlaying: Boolean
)

/**
 * UI-specific Spotify track with resolved audio info
 */
data class SpotifyUITrack(
    val track: SpotifyApiTrack,
    val resolvedStream: ResolvedStreamInfo? = null,
    val source: TrackSource = TrackSource.SPOTIFY_HYBRID
)

enum class TrackSource {
    SPOTIFY_NATIVE,      // Direct Spotify stream (Premium + SDK)
    SPOTIFY_HYBRID,      // Spotify metadata + YouTube Music audio
    YOUTUBE_MUSIC,       // YouTube Music native
    LOCAL,               // Local file
    ADDON                // BitChord addon
}

data class ResolvedStreamInfo(
    val url: String,
    val codec: String,
    val bitrateKbps: Int,
    val isLossless: Boolean,
    val source: TrackSource
)

/**
 * Spotify colors for theming
 */
object SpotifyColors {
    val GREEN = Color(0xFF1DB954)
    val GREEN_DARK = Color(0xFF1AA34A)
    val GREEN_LIGHT = Color(0xFF2ECC71)
    val BLACK = Color(0xFF121212)
    val DARK_GRAY = Color(0xFF181818)
    val MEDIUM_GRAY = Color(0xFF282828)
    val LIGHT_GRAY = Color(0xFFB3B3B3)
    val WHITE = Color(0xFFFFFFFF)
    val ERROR_RED = Color(0xFFE22134)
}

/**
 * Spotify theme presets for Kilo's appearance system
 */
object SpotifyThemePresets {
    // These can be added to Kilo's SkinTheme enum
    // SUNSET_GLASS, SPOTIFY_FLOW, YT_MUSIC, etc.

    val SPOTIFY_COLORS = mapOf(
        "primary" to SpotifyColors.GREEN,
        "primaryDark" to SpotifyColors.GREEN_DARK,
        "primaryLight" to SpotifyColors.GREEN_LIGHT,
        "background" to SpotifyColors.BLACK,
        "surface" to SpotifyColors.DARK_GRAY,
        "surfaceVariant" to SpotifyColors.MEDIUM_GRAY,
        "onPrimary" to SpotifyColors.BLACK,
        "onSurface" to SpotifyColors.WHITE,
        "onSurfaceVariant" to SpotifyColors.LIGHT_GRAY,
        "error" to SpotifyColors.ERROR_RED
    )
}

/**
 * Spotify source badge for UI
 */
@Composable
fun SpotifySourceBadge(
    modifier: Modifier = Modifier,
    badgeSize: Int = 12
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.size(badgeSize.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(badgeSize.dp)) {
            val radius = badgeSize / 2f
            drawCircle(
                color = SpotifyColors.GREEN,
                radius = radius
            )
            drawCircle(
                color = SpotifyColors.BLACK,
                radius = radius * 0.6f
            )
            // Draw three curved lines (sound waves)
            val strokeWidth = max(1f, badgeSize / 10f)
            val centerX = badgeSize / 2f
            val centerY = badgeSize / 2f
            for (i in 0..2) {
                val r = radius * 0.3f + i * (radius * 0.2f)
                drawArc(
                    color = SpotifyColors.GREEN,
                    startAngle = -120f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = Offset(centerX - r, centerY - r),
                    size = Size(2 * r, 2 * r),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }
    }
}

/**
 * YouTube Music source badge for UI
 */
@Composable
fun YouTubeMusicSourceBadge(
    modifier: Modifier = Modifier,
    badgeSize: Int = 12
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.size(badgeSize.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(badgeSize.dp)) {
            val radius = badgeSize / 2f
            drawCircle(
                color = Color(0xFFFF0000),
                radius = radius
            )
            // Play triangle
            val triangleSize = badgeSize * 0.4f
            val centerX = badgeSize / 2f + badgeSize * 0.05f
            val centerY = badgeSize / 2f
            val path = Path().apply {
                moveTo(centerX - triangleSize / 2, centerY - triangleSize / 2)
                lineTo(centerX - triangleSize / 2, centerY + triangleSize / 2)
                lineTo(centerX + triangleSize / 2, centerY)
                close()
            }
            drawPath(
                path = path,
                color = Color.White,
                style = Fill
            )
        }
    }
}

/**
 * Hybrid source badge (shows both sources available)
 */
@Composable
fun HybridSourceBadge(
    modifier: Modifier = Modifier,
    badgeSize: Int = 12,
    hasSpotify: Boolean = true,
    hasYouTubeMusic: Boolean = true
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (hasSpotify) {
            SpotifySourceBadge(badgeSize = badgeSize)
        }
        if (hasYouTubeMusic) {
            YouTubeMusicSourceBadge(badgeSize = badgeSize)
        }
    }
}

/**
 * Spotify Library Models for Kilo's Library Tab
 */
data class SpotifyLibrary(
    val likedSongs: List<SpotifySavedTrack> = emptyList(),
    val playlists: List<SpotifyPlaylistSummary> = emptyList(),
    val albums: List<SpotifySavedAlbum> = emptyList(),
    val artists: List<SpotifyFollowedArtist> = emptyList(),
    val lastSynced: Long = 0,
    val isSyncing: Boolean = false
)

data class SpotifySavedTrack(
    val addedAt: String,
    val track: BridgeSpotifyTrack
)

data class SpotifySavedAlbum(
    val addedAt: String,
    val album: BridgeSpotifyAlbum,
    val artists: List<BridgeSpotifyArtist>
)

data class SpotifyFollowedArtist(
    val artist: BridgeSpotifyArtist
)

/**
 * Extension to convert Spotify library items to Kilo's SongItemClean for unified UI
 */
fun SpotifySavedTrack.toSongItemClean(): SongItemClean {
    val t = track
    return SongItemClean(
        id = "spotify_saved_${t.uri}",
        title = t.name,
        artist = t.artists.map { it.name }.joinToString(", "),
        album = t.album?.name ?: "",
        coverUrl = t.image ?: "",
        durationMs = t.durationMs,
        videoId = "",
        source = "spotify",
        uri = t.uri,
        isDownloaded = false,
        isExplicit = t.explicit,
        spotifyUri = t.uri,
        spotifyId = t.uid
    )
}

fun SpotifySavedAlbum.toSongItemClean(): SongItemClean {
    return SongItemClean(
        id = "spotify_album_${album.uri}",
        title = album.name,
        artist = artists.map { it.name }.joinToString(", "),
        album = album.name,
        coverUrl = album.uri.let { "https://i.scdn.co/image/${it.removePrefix("spotify:album:")}" },
        durationMs = 0,
        videoId = "",
        source = "spotify",
        uri = album.uri,
        isDownloaded = false,
        isAlbum = true
    )
}

fun SpotifyFollowedArtist.toSongItemClean(): SongItemClean {
    return SongItemClean(
        id = "spotify_artist_${artist.uri}",
        title = artist.name,
        artist = "Artist",
        album = "",
        coverUrl = artist.uri.let { "https://i.scdn.co/image/${it.removePrefix("spotify:artist:")}" },
        durationMs = 0,
        videoId = "",
        source = "spotify",
        uri = artist.uri,
        isDownloaded = false,
        isArtist = true
    )
}

fun SpotifyPlaylistSummary.toSongItemClean(): SongItemClean {
    return SongItemClean(
        id = "spotify_playlist_${id}",
        title = name,
        artist = "${trackCount} songs",
        album = owner,
        coverUrl = coverUrl,
        durationMs = 0,
        videoId = "",
        source = "spotify",
        uri = id,
        isDownloaded = false,
        isPlaylist = true
    )
}

fun SpotifyAlbumSummary.toSongItemClean(): SongItemClean {
    return SongItemClean(
        id = "spotify_album_${id}",
        title = name,
        artist = artist,
        album = name,
        coverUrl = coverUrl,
        durationMs = 0,
        videoId = "",
        source = "spotify",
        uri = id,
        isDownloaded = false,
        isAlbum = true
    )
}

/**
 * Extension to convert HybridTrack to SongItemClean for unified UI
 */
fun HybridTrack.toSongItemClean(): SongItemClean {
    return SongItemClean(
        id = canonicalId,
        title = title,
        artist = artist,
        album = album,
        coverUrl = coverUrl,
        durationMs = durationMs,
        videoId = rawId,
        source = sourceId,
        uri = canonicalId,
        isDownloaded = false,
        isExplicit = isExplicit
    )
}