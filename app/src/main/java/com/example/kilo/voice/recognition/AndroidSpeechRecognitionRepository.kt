package com.example.kilo.voice.recognition

import android.content.Context

class AndroidSpeechRecognitionRepository(context: Context) : RecognitionRepository {
    private val manager = SpeechRecognizerManager(context)
    override fun start(listener: RecognitionRepository.Listener) = manager.start(listener)
    override fun stop() = manager.stop()
    override fun destroy() = manager.destroy()
}
