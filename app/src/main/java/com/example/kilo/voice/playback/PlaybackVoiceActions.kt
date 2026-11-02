package com.example.kilo.voice.playback

import com.example.kilo.MainViewModel

class PlaybackVoiceActions(private val viewModel: MainViewModel) {
    fun playOrResume() = viewModel.resumeFromVoiceCommand()
    fun pause() = viewModel.pauseFromVoiceCommand()
    fun next() = viewModel.nextTrack()
    fun previous() = viewModel.prevTrack()
    fun seekTo(positionMs: Long) {
        val duration = viewModel.state.value.currentDurationMs.coerceAtLeast(1L)
        viewModel.seekTo((positionMs.toFloat() / duration).coerceIn(0f, 1f))
    }
    fun seekBy(deltaMs: Long) = seekTo(viewModel.state.value.currentPosMs + deltaMs)
    fun repeatOne() = viewModel.setRepeatCurrentTrack(true)
    fun repeatAll() = viewModel.setRepeatCurrentTrack(false)
    fun repeatOff() = viewModel.setVoiceRepeatModeOff()
    fun shuffle(enabled: Boolean) = viewModel.setVoiceShuffle(enabled)
    fun mute(muted: Boolean) = viewModel.setVoiceMuted(muted)
}
