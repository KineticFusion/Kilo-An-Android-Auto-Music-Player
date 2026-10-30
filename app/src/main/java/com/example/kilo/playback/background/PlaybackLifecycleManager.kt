package com.example.kilo.playback.background

import androidx.media3.common.Player

/** Diagnostics around the app's existing MediaLibraryService lifecycle. */
class PlaybackLifecycleManager(private val player: Player) {
    fun serviceCreated() = BackgroundPlaybackMetrics.event(
        "service_created", "queue=${player.mediaItemCount} playing=${player.isPlaying}"
    )

    fun serviceStarted(startId: Int) = BackgroundPlaybackMetrics.event(
        "service_started", "startId=$startId queue=${player.mediaItemCount} playWhenReady=${player.playWhenReady}"
    )

    fun taskRemoved() = BackgroundPlaybackMetrics.event(
        "activity_task_removed", "queue=${player.mediaItemCount} playing=${player.isPlaying}"
    )

    fun serviceDestroyed() = BackgroundPlaybackMetrics.event(
        "service_destroyed", "queue=${player.mediaItemCount} playing=${player.isPlaying}"
    )
}
