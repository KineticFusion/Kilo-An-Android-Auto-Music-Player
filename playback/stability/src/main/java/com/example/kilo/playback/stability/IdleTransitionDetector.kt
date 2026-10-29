package com.example.kilo.playback.stability

import androidx.media3.common.Player

data class IdleTransitionReport(
    val rootCause: IdleRootCause,
    val protection: RecoveryProtectionDecision,
    val previous: PlaybackHealthSnapshot?,
    val current: PlaybackHealthSnapshot
)

/** Detects READY/BUFFERING → IDLE transitions from the watchdog's recorded evidence. */
object IdleTransitionDetector {
    fun detect(
        previousState: Int,
        currentState: Int,
        previous: PlaybackHealthSnapshot?,
        current: PlaybackHealthSnapshot
    ): IdleTransitionReport? {
        if (currentState != Player.STATE_IDLE ||
            previousState !in setOf(Player.STATE_READY, Player.STATE_BUFFERING)
        ) return null
        val prior = previous ?: PlaybackWatchdog.latest(current.mediaId)
        return IdleTransitionReport(
            rootCause = IdleRootCauseAnalyzer.classify(prior, current),
            protection = RecoveryProtectionLayer.unexpectedIdleDecision(current.mediaId),
            previous = prior,
            current = current
        )
    }
}
