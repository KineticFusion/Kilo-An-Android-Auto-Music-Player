package com.example.kilo.voice.recognition

class RecognitionController(private val repository: RecognitionRepository) {
    fun begin(listener: RecognitionRepository.Listener) = repository.start(listener)
    fun stop() = repository.stop()
    fun close() = repository.destroy()
}
