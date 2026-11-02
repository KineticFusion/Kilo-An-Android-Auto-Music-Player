package com.example.kilo.voice.assistant

import android.util.Log
import com.example.kilo.voice.domain.VoiceIntent

/** Local, privacy-preserving diagnostics. Spoken text and media names are never recorded. */
object AssistantAnalytics {
    fun record(intent: VoiceIntent, success: Boolean, elapsedMs: Long) {
        Log.i("KiloAssistant", "intent=$intent success=$success elapsedMs=$elapsedMs")
    }
}
