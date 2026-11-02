package com.example.kilo.playback.preparation

/** Keeps the preparation cache and the single standby decoder in agreement. */
class PreloadCoordinator(
    val cache: PreparedTrackCache = PreparedTrackCache(),
    val decoderPool: DecoderPool = DecoderPool()
) {
    fun forget(trackId: String) {
        if (cache.get(trackId) != null) cache.clear()
        decoderPool.release(trackId)
    }
}
