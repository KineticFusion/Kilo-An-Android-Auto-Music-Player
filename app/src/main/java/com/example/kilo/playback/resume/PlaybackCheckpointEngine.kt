package com.example.kilo.playback.resume

import android.content.Context
import androidx.media3.common.Player

/** Checkpoint facade for the existing queue/session persistence path. */
class PlaybackCheckpointEngine(context: Context) {
    private val persistence = SessionPersistence(context.applicationContext)

    /** Must be called on the player's application looper because it snapshots the live queue. */
    fun checkpoint(player: Player, reason: String): PlaybackSnapshot? = persistence.save(player, reason)
}
