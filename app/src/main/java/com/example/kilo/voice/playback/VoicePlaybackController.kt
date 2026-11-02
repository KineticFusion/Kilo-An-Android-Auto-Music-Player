package com.example.kilo.voice.playback

import com.example.kilo.MainViewModel
import com.example.kilo.voice.domain.VoiceCommand
import com.example.kilo.voice.domain.VoiceIntent

class VoicePlaybackController(viewModel: MainViewModel) {
    private val actions = PlaybackVoiceActions(viewModel)

    fun execute(command: VoiceCommand): String? = when (command.intent) {
        VoiceIntent.PLAY, VoiceIntent.RESUME -> { actions.playOrResume(); "Playing." }
        VoiceIntent.PAUSE -> { actions.pause(); "Paused." }
        VoiceIntent.SKIP -> { actions.next(); "Skipping to the next song." }
        VoiceIntent.PREVIOUS -> { actions.previous(); "Going back one song." }
        VoiceIntent.SEEK_FORWARD -> { actions.seekBy(command.amountMs ?: 30_000L); "Skipping ahead." }
        VoiceIntent.SEEK_BACKWARD -> {
            actions.seekBy(-(command.amountMs ?: 30_000L))
            "Going back in the song."
        }
        VoiceIntent.SEEK_TO -> { actions.seekTo(command.amountMs ?: 0L); "Moving to that part of the song." }
        VoiceIntent.SHUFFLE -> { actions.shuffle(command.enabled ?: true); if (command.enabled == false) "Shuffle off." else "Shuffle on." }
        VoiceIntent.REPEAT, VoiceIntent.REPEAT_QUEUE, VoiceIntent.REPEAT_ALL -> { actions.repeatAll(); "Repeat all is on." }
        VoiceIntent.REPEAT_ONE -> { actions.repeatOne(); "Repeating this song." }
        VoiceIntent.REPEAT_OFF -> { actions.repeatOff(); "Repeat is off." }
        VoiceIntent.MUTE -> { actions.mute(true); "Muted." }
        VoiceIntent.UNMUTE -> { actions.mute(false); "Sound is on." }
        else -> null
    }
}
