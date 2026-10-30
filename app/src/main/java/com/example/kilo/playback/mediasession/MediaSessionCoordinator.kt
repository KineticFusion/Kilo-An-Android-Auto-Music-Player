package com.example.kilo.playback.mediasession

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import com.example.kilo.playback.background.BackgroundPlaybackCoordinator

/** Session facade: external transport and queue commands pass through the background coordinator. */
class MediaSessionCoordinator(
    delegate: Player,
    private val commands: BackgroundPlaybackCoordinator
) : ForwardingPlayer(delegate) {
    override fun play() = commands.play()
    override fun pause() = commands.pause()
    override fun prepare() = commands.prepare()
    override fun stop() = commands.stop()
    override fun setPlayWhenReady(playWhenReady: Boolean) = commands.setPlayWhenReady(playWhenReady)
    override fun setRepeatMode(repeatMode: Int) = commands.setRepeatMode(repeatMode)
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = commands.setShuffle(shuffleModeEnabled)

    override fun seekTo(positionMs: Long) = commands.seekTo(positionMs)
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = commands.seekTo(mediaItemIndex, positionMs)
    override fun seekToDefaultPosition() = commands.seekToDefaultPosition()
    override fun seekToDefaultPosition(mediaItemIndex: Int) = commands.seekToDefaultPosition(mediaItemIndex)
    override fun seekToNextMediaItem() = commands.next()
    override fun seekToPreviousMediaItem() = commands.previous()
    override fun seekToNextWindow() = commands.seekNextWindow()
    override fun seekToPreviousWindow() = commands.seekPreviousWindow()
    override fun seekToNext() = commands.seekNext()
    override fun seekToPrevious() = commands.seekPrevious()

    override fun setMediaItems(mediaItems: List<MediaItem>) = commands.setMediaItems(mediaItems)
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) =
        commands.setMediaItems(mediaItems, resetPosition)
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
        commands.setMediaItems(mediaItems, startIndex, startPositionMs)
    override fun setMediaItem(mediaItem: MediaItem) = commands.setMediaItem(mediaItem)
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = commands.setMediaItem(mediaItem, startPositionMs)
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = commands.setMediaItem(mediaItem, resetPosition)
    override fun addMediaItem(mediaItem: MediaItem) = commands.addMediaItem(mediaItem)
    override fun addMediaItem(index: Int, mediaItem: MediaItem) = commands.addMediaItem(index, mediaItem)
    override fun addMediaItems(mediaItems: List<MediaItem>) = commands.addMediaItems(mediaItems)
    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) = commands.addMediaItems(index, mediaItems)
    override fun moveMediaItem(currentIndex: Int, newIndex: Int) = commands.moveMediaItem(currentIndex, newIndex)
    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) = commands.moveMediaItems(fromIndex, toIndex, newIndex)
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) = commands.replaceMediaItem(index, mediaItem)
    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) =
        commands.replaceMediaItems(fromIndex, toIndex, mediaItems)
    override fun removeMediaItem(index: Int) = commands.removeMediaItem(index)
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) = commands.removeMediaItems(fromIndex, toIndex)
    override fun clearMediaItems() = commands.clearMediaItems()
}
