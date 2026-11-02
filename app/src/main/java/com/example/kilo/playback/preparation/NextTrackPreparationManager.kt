package com.example.kilo.playback.preparation

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import android.os.SystemClock

/** Standby deck preparation happens before the fade window; it never mutates the main queue. */
class NextTrackPreparationManager(private val standby: Player, private val cache: PreparedTrackCache) {
    fun prepare(item: MediaItem) {
        val started = SystemClock.elapsedRealtime()
        standby.volume = 0f
        standby.setMediaItem(item)
        standby.prepare()
        StreamPreparationMetrics.prepared(item.mediaId, SystemClock.elapsedRealtime() - started, 0L)
    }
    fun snapshot(): PreparedTrack? = standby.currentMediaItem?.let {
        PreparedTrack(it, SystemClock.elapsedRealtime(), (standby.bufferedPosition - standby.currentPosition).coerceAtLeast(0L), standby.playbackState == Player.STATE_READY)
            .also(cache::put)
    }
}
