package com.example.kilo.voice.recognition

import android.os.Bundle
import android.speech.SpeechRecognizer

object SpeechResultMapper {
    fun bestTranscript(bundle: Bundle?): String = bundle
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()

    fun partialTranscript(bundle: Bundle?): String = bestTranscript(bundle)
}
