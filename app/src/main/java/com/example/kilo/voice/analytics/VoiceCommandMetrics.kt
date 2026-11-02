package com.example.kilo.voice.analytics

import android.util.Log
import com.example.kilo.voice.domain.VoiceCommand
import com.example.kilo.voice.domain.VoiceExecution

/** Local diagnostic log only; transcripts are deliberately not persisted or uploaded. */
object VoiceCommandMetrics {
    fun record(command: VoiceCommand, result: VoiceExecution, elapsedMs: Long) {
        Log.i("KiloVoice", "intent=${command.intent} success=${result.success} elapsedMs=$elapsedMs")
    }
}
