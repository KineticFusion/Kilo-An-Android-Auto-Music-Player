package com.example.kilo.spotify.mapping

import com.example.kilo.spotify.*
import com.example.kilo.streaming.HybridTrack
import com.example.kilo.streaming.ConfiguredSource

object SpotifyMapper {
    fun toHybridTrack(track: SpotifyTrack, sourceConfig: ConfiguredSource): HybridTrack {
        val artistNames = track.artists.map { it.name }.joinToString(", ")
        val albumName = track.album?.name ?: ""
        val bestCoverUrl = extractBestImage(track.album?.images ?: track.images)

        return HybridTrack(
            canonicalId = "spotify:${track.id}",
            rawId = track.id,
            sourceId = sourceConfig.id,
            title = track.name,
            artist = artistNames,
            album = albumName,
            coverUrl = bestCoverUrl,
            durationMs = track.durationMs,
            codecBadge = "Spotify • Hybrid",
            isLossless = false,
            isExplicit = track.explicit
        )
    }

    fun toHybridTracks(tracks: List<SpotifyTrack>, sourceConfig: ConfiguredSource): List<HybridTrack> {
        return tracks.map { toHybridTrack(it, sourceConfig) }
    }

    fun toHybridTrackFromPlaylistTrack(playlistTrack: SpotifyPlaylistTrack, sourceConfig: ConfiguredSource): HybridTrack? {
        return playlistTrack.track?.let { toHybridTrack(it, sourceConfig) }
    }

    fun toHybridTracksFromPlaylistTracks(playlistTracks: List<SpotifyPlaylistTrack>, sourceConfig: ConfiguredSource): List<HybridTrack> {
        return playlistTracks.mapNotNull { toHybridTrackFromPlaylistTrack(it, sourceConfig) }
    }

    private fun extractBestImage(images: List<SpotifyImage>): String {
        return images.maxByOrNull { it.width * it.height }?.url
            ?: images.firstOrNull()?.url
            ?: ""
    }

    fun toSongItemClean(track: SpotifyTrack): com.example.kilo.SongItemClean {
        val artistNames = track.artists.map { it.name }.joinToString(", ")
        val albumName = track.album?.name ?: ""
        val bestCoverUrl = extractBestImage(track.album?.images ?: track.images)

        return com.example.kilo.SongItemClean(
            id = "spotify_${track.id}",
            title = track.name,
            artist = artistNames,
            album = albumName,
            coverUrl = bestCoverUrl,
            durationMs = track.durationMs,
            videoId = "",
            source = "spotify",
            uri = track.uri,
            isDownloaded = false,
            isExplicit = track.explicit,
            spotifyUri = track.uri,
            spotifyId = track.id
        )
    }

    fun toSongItemCleanFromPlaylist(playlist: SpotifyPlaylist): com.example.kilo.SongItemClean {
        return com.example.kilo.SongItemClean(
            id = "spotify_playlist_${playlist.id}",
            title = playlist.name,
            artist = "${playlist.trackCount} songs",
            album = playlist.owner?.displayName ?: "Unknown",
            coverUrl = extractBestImage(playlist.images),
            durationMs = 0,
            videoId = "",
            source = "spotify",
            uri = playlist.uri,
            isDownloaded = false,
            isPlaylist = true
        )
    }

    fun toSongItemCleanFromAlbum(album: SpotifyAlbum): com.example.kilo.SongItemClean {
        return com.example.kilo.SongItemClean(
            id = "spotify_album_${album.id}",
            title = album.name,
            artist = album.artists.map { it.name }.joinToString(", "),
            album = album.name,
            coverUrl = extractBestImage(album.images),
            durationMs = 0,
            videoId = "",
            source = "spotify",
            uri = album.uri,
            isDownloaded = false,
            isAlbum = true
        )
    }

    fun toSongItemCleanFromArtist(artist: SpotifyArtist): com.example.kilo.SongItemClean {
        return com.example.kilo.SongItemClean(
            id = "spotify_artist_${artist.id}",
            title = artist.name,
            artist = "Artist",
            album = "",
            coverUrl = extractBestImage(artist.images),
            durationMs = 0,
            videoId = "",
            source = "spotify",
            uri = artist.uri,
            isDownloaded = false,
            isArtist = true
        )
    }

    fun toSpotifyPlaylistSummary(playlist: SpotifyPlaylist): com.example.kilo.spotify.SpotifyPlaylistSummary {
        return com.example.kilo.spotify.SpotifyPlaylistSummary(
            id = playlist.id,
            name = playlist.name,
            coverUrl = extractBestImage(playlist.images),
            trackCount = playlist.trackCount
        )
    }

    fun toSpotifyAlbumSummary(album: SpotifyAlbum): com.example.kilo.spotify.SpotifyAlbumSummary {
        return com.example.kilo.spotify.SpotifyAlbumSummary(
            id = album.id,
            name = album.name,
            artist = album.artists.map { it.name }.joinToString(", "),
            coverUrl = extractBestImage(album.images),
            totalTracks = album.totalTracks,
            releaseDate = album.releaseDate
        )
    }
}