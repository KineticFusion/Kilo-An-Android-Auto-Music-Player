package com.example.kilo.playback.crossfade

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer

/** Top-level lifecycle facade for the bounded two-deck transition engine. */
class CrossfadeOrchestrator(context: Context, primary: ExoPlayer, standby: ExoPlayer) {
    private val engine = DualDeckEngine(context.applicationContext, primary, standby)
    fun tick(): Long = engine.tick()
    fun cancel(reason: String) = engine.cancel(reason)
    fun onStop() = engine.onStop()
}
