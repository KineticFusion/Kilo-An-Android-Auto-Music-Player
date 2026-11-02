package com.example.kilo.playback.crossfade

import com.example.kilo.SongItemClean

enum class TransitionMode { GAPLESS, CROSSFADE, NATURAL }

data class TransitionPlan(val mode: TransitionMode, val durationMs: Long, val reason: String)

object TransitionPolicy {
    fun decide(
        current: SongItemClean,
        next: SongItemClean,
        requestedDurationMs: Long,
        smart: Boolean,
        gapless: Boolean,
        genreAware: Boolean = true
    ): TransitionPlan {
        if (requestedDurationMs <= 0L) return TransitionPlan(TransitionMode.NATURAL, 0L, "crossfade_off")
        val currentAlbum = current.albumBrowseId.ifBlank { current.album }.trim()
        val nextAlbum = next.albumBrowseId.ifBlank { next.album }.trim()
        val sameAlbum = currentAlbum.isNotBlank() && currentAlbum.equals(nextAlbum, ignoreCase = true)
        val liveOrLongForm = sequenceOf(current.title, next.title, current.album, next.album, current.artist, next.artist)
            .any { value -> listOf("live", "concert", "classical", "audiobook", "podcast", "continuous mix").any { value.contains(it, true) } }
        if (gapless && (sameAlbum || liveOrLongForm)) {
            return TransitionPlan(TransitionMode.GAPLESS, 0L, if (sameAlbum) "same_album" else "live_or_long_form")
        }
        if (!smart) return TransitionPlan(TransitionMode.CROSSFADE, requestedDurationMs, "fixed_duration")
        if (!genreAware) return TransitionPlan(TransitionMode.CROSSFADE, requestedDurationMs, "genre_profile_off")
        val genre = TrackAnalyzer.genreHint(current.title + " " + current.album)
        val profileDuration = when (genre) {
            "classical" -> if (gapless) 0L else requestedDurationMs
            "edm" -> requestedDurationMs.coerceIn(6_000L, 8_000L)
            "hip-hop", "hip hop" -> 3_000L
            "rock" -> 3_500L
            "acoustic" -> 2_000L
            "jazz" -> 2_500L
            "pop" -> 4_500L
            else -> requestedDurationMs
        }
        return if (profileDuration == 0L && gapless) TransitionPlan(TransitionMode.GAPLESS, 0L, "genre_classical")
        else TransitionPlan(TransitionMode.CROSSFADE, profileDuration, "smart_${genre ?: "default"}")
    }
}
