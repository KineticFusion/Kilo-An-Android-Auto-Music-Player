package com.example.kilo.voice.intent

import com.example.kilo.voice.domain.VoiceCommand
import com.example.kilo.voice.domain.VoiceContext

class VoiceIntentRouter {
    fun route(transcript: String, context: VoiceContext): VoiceCommand =
        IntentResolver.resolve(IntentParser.parse(transcript), context)
}
