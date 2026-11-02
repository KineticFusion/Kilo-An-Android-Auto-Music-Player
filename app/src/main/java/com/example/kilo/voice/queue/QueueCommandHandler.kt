package com.example.kilo.voice.queue

import com.example.kilo.voice.domain.VoiceCommand
import com.example.kilo.voice.domain.VoiceIntent

class QueueCommandHandler(private val controller: QueueVoiceController) {
    suspend fun execute(command: VoiceCommand): String? = when (command.intent) {
        VoiceIntent.SHOW_QUEUE -> { controller.show(); "Here’s what’s coming up." }
        VoiceIntent.CLEAR_QUEUE -> if (controller.clearUpcoming()) "Cleared the songs after the current one." else "There are no upcoming songs to clear."
        VoiceIntent.SHUFFLE_QUEUE -> { controller.shuffle(); "Shuffled the queue." }
        VoiceIntent.REPEAT_QUEUE -> { controller.repeat(); "The queue will repeat." }
        VoiceIntent.ADD_NEXT -> if (controller.add(command.entity.orEmpty(), true)) "Added to play next." else "I couldn’t find a playable match."
        VoiceIntent.ADD_TO_QUEUE -> if (controller.add(command.entity.orEmpty(), false)) "Added to the end of the queue." else "I couldn’t find a playable match."
        VoiceIntent.MOVE_TO_TOP -> if (controller.move(command.entity.orEmpty(), true)) "Moved it near the top of the queue." else "I couldn’t find that upcoming song."
        VoiceIntent.MOVE_TO_BOTTOM -> if (controller.move(command.entity.orEmpty(), false)) "Moved it to the end of the queue." else "I couldn’t find that upcoming song."
        VoiceIntent.REMOVE_TRACK -> if (controller.remove(command.entity.orEmpty())) "Removed it from the queue." else "I couldn’t find that upcoming song."
        else -> null
    }
}
