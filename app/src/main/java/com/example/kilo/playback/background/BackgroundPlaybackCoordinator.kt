package com.example.kilo.playback.background

import android.content.Context
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.example.kilo.playback.resume.SessionPersistence

/** Command and persistence wrapper around the existing ExoPlayer owned by PlayerHolder. */
class BackgroundPlaybackCoordinator(
    context: Context,
    private val player: Player,
    private val handler: Handler
) : Player.Listener {
    private val persistence = SessionPersistence(context)
    private val saveSnapshot = Runnable { persist("coalesced_player_change") }
    private var attached = false

    fun attach() {
        if (attached) return
        attached = true
        player.addListener(this)
        BackgroundPlaybackMetrics.event("ownership_attached", "player=${System.identityHashCode(player)}")
    }

    fun close() {
        handler.removeCallbacks(saveSnapshot)
        persist("service_destroy")
        if (attached) player.removeListener(this)
        attached = false
        BackgroundPlaybackMetrics.event("ownership_detached", "player=${System.identityHashCode(player)}")
    }

    fun play() = command("play") { player.play() }
    fun pause() = command("pause") { player.pause() }
    fun toggle() = if (player.playWhenReady) pause() else play()
    fun prepare() = command("prepare") { player.prepare() }
    fun stop() = command("stop") { player.stop() }
    fun setPlayWhenReady(value: Boolean) = command("play_when_ready", "value=$value") { player.playWhenReady = value }

    fun seekTo(positionMs: Long) = command("seek", "positionMs=$positionMs") {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun seekToDefaultPosition() = command("seek_default") { player.seekToDefaultPosition() }
    fun seekToDefaultPosition(index: Int) = command("seek_default", "index=$index") {
        player.seekToDefaultPosition(index)
    }

    fun seekTo(index: Int, positionMs: Long) = command("seek", "index=$index positionMs=$positionMs") {
        player.seekTo(index, positionMs.coerceAtLeast(0L))
    }

    fun seekBy(deltaMs: Long) = seekTo((player.currentPosition + deltaMs).coerceAtLeast(0L))

    fun next(playAfter: Boolean = false) = command("next", "playAfter=$playAfter") {
        if (player.mediaItemCount > 0) player.seekToNextMediaItem()
        if (playAfter) player.play()
    }

    fun previous(playAfter: Boolean = false) = command("previous", "playAfter=$playAfter") {
        if (player.mediaItemCount > 0) player.seekToPreviousMediaItem()
        if (playAfter) player.play()
    }

    fun seekNextWindow(playAfter: Boolean = false) = command("next_window") {
        player.seekToNextWindow()
        if (playAfter) player.play()
    }

    fun seekPreviousWindow(playAfter: Boolean = false) = command("previous_window") {
        player.seekToPreviousWindow()
        if (playAfter) player.play()
    }

    fun seekNext(playAfter: Boolean = false) = command("next") {
        player.seekToNext()
        if (playAfter) player.play()
    }

    fun seekPrevious(playAfter: Boolean = false) = command("previous") {
        player.seekToPrevious()
        if (playAfter) player.play()
    }

    fun setRepeatMode(mode: Int) = command("repeat", "mode=$mode") { player.repeatMode = mode }
    fun setShuffle(enabled: Boolean) = command("shuffle", "enabled=$enabled") { player.shuffleModeEnabled = enabled }

    fun setMediaItems(items: List<MediaItem>) = mutateQueue("set_media_items") { player.setMediaItems(items) }
    fun setMediaItems(items: List<MediaItem>, resetPosition: Boolean) =
        mutateQueue("set_media_items") { player.setMediaItems(items, resetPosition) }
    fun setMediaItems(items: List<MediaItem>, index: Int, positionMs: Long) =
        mutateQueue("set_media_items") { player.setMediaItems(items, index, positionMs) }
    fun setMediaItem(item: MediaItem) = mutateQueue("set_media_item") { player.setMediaItem(item) }
    fun setMediaItem(item: MediaItem, positionMs: Long) =
        mutateQueue("set_media_item") { player.setMediaItem(item, positionMs) }
    fun setMediaItem(item: MediaItem, resetPosition: Boolean) =
        mutateQueue("set_media_item") { player.setMediaItem(item, resetPosition) }
    fun addMediaItem(item: MediaItem) = mutateQueue("add_media_item") { player.addMediaItem(item) }
    fun addMediaItem(index: Int, item: MediaItem) = mutateQueue("add_media_item") { player.addMediaItem(index, item) }
    fun addMediaItems(items: List<MediaItem>) = mutateQueue("add_media_items") { player.addMediaItems(items) }
    fun addMediaItems(index: Int, items: List<MediaItem>) =
        mutateQueue("add_media_items") { player.addMediaItems(index, items) }
    fun moveMediaItem(from: Int, to: Int) = mutateQueue("move_media_item") { player.moveMediaItem(from, to) }
    fun moveMediaItems(from: Int, to: Int, newIndex: Int) =
        mutateQueue("move_media_items") { player.moveMediaItems(from, to, newIndex) }
    fun replaceMediaItem(index: Int, item: MediaItem) =
        mutateQueue("replace_media_item") { player.replaceMediaItem(index, item) }
    fun replaceMediaItems(from: Int, to: Int, items: List<MediaItem>) =
        mutateQueue("replace_media_items") { player.replaceMediaItems(from, to, items) }
    fun removeMediaItem(index: Int) = mutateQueue("remove_media_item") { player.removeMediaItem(index) }
    fun removeMediaItems(from: Int, to: Int) = mutateQueue("remove_media_items") { player.removeMediaItems(from, to) }
    fun clearMediaItems() = mutateQueue("clear_media_items") { player.clearMediaItems() }

    fun enqueueCurrentNext(): Boolean {
        val current = player.currentMediaItem ?: return false
        if (player.mediaItemCount <= 0) return false
        val index = (player.currentMediaItemIndex.coerceAtLeast(0) + 1).coerceAtMost(player.mediaItemCount)
        addMediaItem(index, current)
        return true
    }

    fun snapshot(reason: String) = persist(reason)

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = scheduleSave("track_transition")
    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = scheduleSave("queue_change")
    override fun onIsPlayingChanged(isPlaying: Boolean) = scheduleSave(if (isPlaying) "play_started" else "play_stopped")
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = scheduleSave("play_intent")
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) = scheduleSave("position_change")

    private inline fun command(name: String, details: String = "", action: () -> Unit) {
        BackgroundPlaybackMetrics.event("command", "name=$name $details")
        action()
        scheduleSave(name)
    }

    private inline fun mutateQueue(name: String, action: () -> Unit) {
        BackgroundPlaybackMetrics.event("queue_command", "name=$name")
        action()
        scheduleSave(name)
    }

    private fun scheduleSave(reason: String) {
        handler.removeCallbacks(saveSnapshot)
        handler.postDelayed(saveSnapshot, SAVE_DEBOUNCE_MS)
    }

    private fun persist(reason: String) {
        runCatching { persistence.save(player, reason) }
            .onFailure { BackgroundPlaybackMetrics.event("snapshot_failed", "reason=$reason error=${it.javaClass.simpleName}") }
    }

    private companion object { const val SAVE_DEBOUNCE_MS = 250L }
}
