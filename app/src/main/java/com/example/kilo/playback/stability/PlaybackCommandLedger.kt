package com.example.kilo.playback.stability

import androidx.media3.common.Player
import com.example.kilo.observability.PlaybackProfiler
import java.util.concurrent.atomic.AtomicLong

/**
 * Records the intent and queue generation around commands sent to the one shared player.
 * It is observational/guard state only; it never issues player commands.
 */
object PlaybackCommandLedger {
    enum class Intent { UNKNOWN, PLAY, PAUSE, STOP }

    private val generation = AtomicLong(0L)
    private val lock = Any()
    @Volatile private var latestIntent = Intent.UNKNOWN
    @Volatile private var intentMediaId: String? = null
    @Volatile private var intentOrigin: String = "unknown"

    fun queueGeneration(): Long = generation.get()

    fun recordQueueMutation(player: Player, origin: String, command: String) {
        val nextGeneration = generation.incrementAndGet()
        val mediaId = player.currentMediaItem?.mediaId
        synchronized(lock) {
            if (intentMediaId != mediaId && latestIntent != Intent.UNKNOWN) {
                latestIntent = Intent.UNKNOWN
            }
            intentMediaId = mediaId
            intentOrigin = origin
        }
        PlaybackProfiler.record(
            "playback_queue_generation",
            mediaId.orEmpty(),
            "generation=$nextGeneration origin=${safe(origin)} command=${safe(command)} itemCount=${player.mediaItemCount}"
        )
    }

    fun recordIntent(player: Player, intent: Intent, origin: String, command: String) {
        val mediaId = player.currentMediaItem?.mediaId
        synchronized(lock) {
            latestIntent = intent
            intentMediaId = mediaId
            intentOrigin = origin
        }
        PlaybackProfiler.record(
            "playback_command_intent",
            mediaId.orEmpty(),
            "intent=$intent origin=${safe(origin)} command=${safe(command)} generation=${generation.get()} " +
                "playWhenReady=${player.playWhenReady} state=${player.playbackState}"
        )
    }

    fun recordCommand(player: Player, origin: String, command: String) {
        PlaybackProfiler.record(
            "playback_command",
            player.currentMediaItem?.mediaId.orEmpty(),
            "origin=${safe(origin)} command=${safe(command)} generation=${generation.get()} " +
                "playWhenReady=${player.playWhenReady} state=${player.playbackState}"
        )
    }

    /** Captures controller identity without logging URLs, headers, or other private source data. */
    fun recordControllerRequest(player: Player, packageName: String, command: Int) {
        PlaybackProfiler.record(
            "playback_controller_request",
            player.currentMediaItem?.mediaId.orEmpty(),
            "origin=media_session controller=${safe(packageName)} command=$command " +
                "generation=${generation.get()} playWhenReady=${player.playWhenReady} state=${player.playbackState}"
        )
    }

    /** Recovery must still match the request's track/queue and must not override an explicit pause or stop. */
    fun mayRecover(
        mediaId: String,
        expectedGeneration: Long,
        playerWantsPlayback: Boolean
    ): Boolean {
        if (generation.get() != expectedGeneration || !playerWantsPlayback) return false
        return synchronized(lock) {
            intentMediaId != mediaId || latestIntent !in setOf(Intent.PAUSE, Intent.STOP)
        }
    }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(80)
}
