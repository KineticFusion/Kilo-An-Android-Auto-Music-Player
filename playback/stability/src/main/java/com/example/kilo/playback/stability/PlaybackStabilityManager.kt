package com.example.kilo.playback.stability

/** Single facade used by phone and Android Auto observers around their shared player. */
object PlaybackStabilityManager {
    fun record(snapshot: PlaybackHealthSnapshot) = PlaybackWatchdog.record(snapshot)
    fun latest(mediaId: String? = null): PlaybackHealthSnapshot? = PlaybackWatchdog.latest(mediaId)
    fun recent(): List<PlaybackHealthSnapshot> = PlaybackWatchdog.recent()

    fun detectIdleTransition(
        previousState: Int,
        currentState: Int,
        previous: PlaybackHealthSnapshot?,
        current: PlaybackHealthSnapshot
    ): IdleTransitionReport? = IdleTransitionDetector.detect(previousState, currentState, previous, current)

    fun unexpectedIdleRecoveryDecision(mediaId: String): RecoveryProtectionDecision =
        RecoveryProtectionLayer.unexpectedIdleDecision(mediaId)
}
