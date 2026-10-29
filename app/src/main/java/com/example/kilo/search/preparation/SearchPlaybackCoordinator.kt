package com.example.kilo.search.preparation

import androidx.media3.common.MediaItem
import com.example.kilo.playback.prepared.ClickToPlayCoordinator

/** Hands a warmed search MediaItem to the existing playback path; null means use its normal fallback. */
object SearchPlaybackCoordinator {
    fun preparedItem(trackId: String): MediaItem? = ClickToPlayCoordinator.mediaItemOrNull(trackId)
}
