package com.example.kilo.playback.stability

import androidx.media3.common.Player
import com.example.kilo.observability.PlaybackProfiler

/** Audit hooks for player mutation call sites; these only log and never wrap or alter commands. */
object MediaSourceAudit {
    fun beforeMutation(operation: String, player: Player, mediaId: String? = player.currentMediaItem?.mediaId) {
        if (!player.playWhenReady && !player.isPlaying) return
        val stack = Throwable().stackTrace
            .dropWhile { it.className == MediaSourceAudit::class.java.name }
            .take(10)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}#${it.methodName}:${it.lineNumber}" }
        observed(
            operation,
            mediaId.orEmpty(),
            player.currentPosition,
            player.playbackState,
            "activeMutation=true caller=${stack.substringBefore(" <- ")} " +
                "thread=${Thread.currentThread().name} stack=[$stack]"
        )
    }

    fun observed(operation: String, mediaId: String, positionMs: Long, playerState: Int, detail: String) {
        PlaybackProfiler.record(
            "media_source_audit",
            mediaId,
            "operation=$operation positionMs=$positionMs playerState=$playerState $detail"
        )
    }
}
