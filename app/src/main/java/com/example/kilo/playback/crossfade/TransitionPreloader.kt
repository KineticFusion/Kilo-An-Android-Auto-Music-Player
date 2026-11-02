package com.example.kilo.playback.crossfade

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
import com.example.kilo.SongItemClean
import com.example.kilo.lyrics.LyricsManager

/** Warms cheap next-track metadata while KILO's existing queue resolver warms the stream. */
object TransitionPreloader {
    fun prepare(context: Context, current: SongItemClean, next: SongItemClean) {
        LyricsManager.prefetch(next)
        if (next.coverUrl.isNotBlank()) {
            val request = ImageRequest.Builder(context.applicationContext)
                .data(next.coverUrl)
                .size(256, 256)
                .memoryCacheKey("harmony-art-${next.id}")
                .diskCacheKey("harmony-art-${next.id}")
                .build()
            context.applicationContext.imageLoader.enqueue(request)
        }
        // Compute and cache the transition choice before the track reaches its ending.
        CrossfadeEngine.transition(context, current, next)
    }
}
