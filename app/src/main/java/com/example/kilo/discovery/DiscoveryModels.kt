package com.example.kilo.discovery

import com.example.kilo.SongItemClean

/** Lightweight playlist signature; deliberately contains no player or network state. */
data class PlaylistDNA(
    val playlistId: String,
    val artistWeights: Map<String, Float>,
    val genreWeights: Map<String, Float>,
    val titleTokens: Set<String>,
    val energy: Float,
    val discoveryAffinity: Float,
    val sourceTrackCount: Int
)

data class DiscoveryMix(
    val id: String,
    val title: String,
    val subtitle: String,
    val songs: List<SongItemClean>,
    val generatedAtMs: Long
)

data class RediscoverPick(
    val id: String,
    val title: String,
    val subtitle: String,
    val songs: List<SongItemClean>
)

data class TasteBlendResult(
    val compatibility: Int,
    val sharedArtists: Set<String>,
    val sharedGenres: Set<String>,
    val discoveryBalance: Float
)

data class MusicCircleSnapshot(
    val circleId: String,
    val memberCount: Int,
    val sharedArtists: List<String>,
    val mix: List<SongItemClean>
)

data class JamSession(
    val id: String,
    val name: String,
    val hostId: String,
    val createdAtMs: Long,
    val queue: List<SongItemClean>,
    val votesByTrack: Map<String, Int> = emptyMap(),
    val active: Boolean = true
)

internal fun discoveryKey(song: SongItemClean): String = song.videoId.ifBlank { song.id }.trim()

internal fun normalizeDiscoveryText(value: String): Set<String> = value.lowercase()
    .split(Regex("[^\\p{L}\\p{N}]+"))
    .asSequence()
    .filter { it.length > 2 }
    .filterNot { it in setOf("the", "and", "for", "from", "with", "feat", "official", "audio", "video", "song", "songs") }
    .toSet()

internal fun discoveryEnergy(song: SongItemClean): Float {
    val text = "${song.title} ${song.artist} ${song.album}".lowercase()
    return when {
        listOf("workout", "gym", "hype", "party", "hardstyle", "phonk", "bass", "power").any { text.contains(it) } -> 0.9f
        listOf("ambient", "sleep", "calm", "piano", "meditation", "rain", "lofi", "focus").any { text.contains(it) } -> 0.2f
        listOf("drive", "road", "night", "indie", "synthwave").any { text.contains(it) } -> 0.58f
        else -> 0.5f
    }
}

internal fun discoveryGenreAffinity(song: SongItemClean, genreWeights: Map<String, Float>): Float {
    if (genreWeights.isEmpty()) return 0f
    val text = "${song.title} ${song.artist} ${song.album}".lowercase()
    val aliases = mapOf(
        "electronic" to listOf("electronic", "edm", "synthwave", "techno", "house", "dance"),
        "hip-hop" to listOf("hip-hop", "hip hop", "hiphop", "rap", "trap", "phonk"),
        "rock" to listOf("rock", "metal", "punk", "alternative"),
        "pop" to listOf("pop", "chart", "dance"),
        "ambient" to listOf("ambient", "lofi", "lo-fi", "piano", "acoustic", "focus"),
        "r&b" to listOf("r&b", "rnb", "soul")
    )
    return genreWeights.entries.fold(0f) { score, (genre, weight) ->
        val normalized = genre.trim().lowercase()
        val hints = aliases[normalized] ?: listOf(normalized)
        if (hints.any { it.isNotBlank() && text.contains(it) }) score + weight.coerceAtLeast(0f) else score
    }.coerceIn(0f, 1f)
}
