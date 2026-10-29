package com.example.kilo.playback.warmup

import android.content.Context
import com.example.kilo.playbackoptimization.PlaybackMetricsManager
import com.example.kilo.playbackoptimization.PreparedTrack
import com.example.kilo.playback.prepared.PreparedPlaybackLayer

/** Screen-specific adapters funnel visible candidates into the existing bounded warmup worker. */
object ScreenWarmupManager {
    fun prepare(
        context: Context,
        tracks: List<PreparedTrack>,
        section: String,
        limit: Int
    ) {
        val candidates = tracks.asSequence().distinctBy { it.mediaId }.take(limit.coerceIn(1, 20)).toList()
        PlaybackMetricsManager.record("screen_warmup_candidates", details = "section=$section count=${candidates.size}")
        PreparedPlaybackLayer.prepareVisible(
            context.applicationContext,
            candidates,
            section,
            candidates.size.coerceAtMost(limit.coerceIn(1, 20))
        )
    }
}

object SearchWarmupManager {
    fun prepare(context: Context, tracks: List<PreparedTrack>) = ScreenWarmupManager.prepare(context, tracks, "search", 10)
}

object PlaylistWarmupManager {
    fun prepare(context: Context, tracks: List<PreparedTrack>) = ScreenWarmupManager.prepare(context, tracks, "playlist", 10)
}

object AlbumWarmupManager {
    fun prepare(context: Context, tracks: List<PreparedTrack>) = ScreenWarmupManager.prepare(context, tracks, "album", 20)
}

object RadioWarmupManager {
    fun prepare(context: Context, tracks: List<PreparedTrack>) = ScreenWarmupManager.prepare(context, tracks, "radio", 20)
}

object MoodWarmupManager {
    fun prepare(context: Context, tracks: List<PreparedTrack>) = ScreenWarmupManager.prepare(context, tracks, "mood", 20)
}
