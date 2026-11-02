package com.example.kilo.voice.queue

import com.example.kilo.MainViewModel

class QueueVoiceController(private val viewModel: MainViewModel) {
    fun show() {
        viewModel.setFullScreenPlayer(true)
        viewModel.togglePlayerOverlayTab(com.example.kilo.OverlayTab.QUEUE)
    }

    fun clearUpcoming(): Boolean = viewModel.clearUpcomingQueueFromVoice()
    fun shuffle() = viewModel.setVoiceShuffle(true)
    fun repeat() = viewModel.setRepeatCurrentTrack(false)
    suspend fun add(query: String, next: Boolean): Boolean = viewModel.addVoiceTrackToQueue(query, next)
    fun move(query: String, toTop: Boolean): Boolean = viewModel.moveVoiceQueueTrack(query, toTop)
    fun remove(query: String): Boolean = viewModel.removeVoiceQueueTrack(query)
}
