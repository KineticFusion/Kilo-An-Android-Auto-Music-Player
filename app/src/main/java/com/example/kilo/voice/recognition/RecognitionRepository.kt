package com.example.kilo.voice.recognition

interface RecognitionRepository {
    interface Listener {
        fun onListening()
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onFailure(message: String)
    }

    fun start(listener: Listener)
    fun stop()
    fun destroy()
}
