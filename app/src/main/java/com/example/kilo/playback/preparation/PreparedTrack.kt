package com.example.kilo.playback.preparation

import androidx.media3.common.MediaItem

data class PreparedTrack(
    val mediaItem: MediaItem,
    val preparedAtMs: Long,
    val bufferedMs: Long,
    val decoderReady: Boolean
) {
    val mediaId: String get() = mediaItem.mediaId
    val readyForFade: Boolean get() = decoderReady && bufferedMs >= 5_000L
}
