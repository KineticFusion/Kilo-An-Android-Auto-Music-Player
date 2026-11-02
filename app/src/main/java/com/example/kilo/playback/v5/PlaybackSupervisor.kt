package com.example.kilo.playback.v5

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Coordinates the wrappers around the current player without replacing its owner or lifecycle. */
object PlaybackSupervisor {
    @Volatile private var initializedPlayer: Player? = null

    fun attach(context: Context, player: ExoPlayer) {
        if (initializedPlayer === player) return
        MainThreadPlayerGateway.attach(player)
        PlaybackStateMonitor.attach(player)
        NetworkTransitionManager.start(context.applicationContext)
        initializedPlayer = player
    }
}
