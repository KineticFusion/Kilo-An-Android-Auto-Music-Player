package com.example.kilo.voice.recognition

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.kilo.voice.settings.VoiceSettingsStore

/** Uses Android's installed recognizer, preferring its on-device model when available. */
class SpeechRecognizerManager(context: Context) : RecognitionRepository {
    private val appContext = context.applicationContext
    private val settings = VoiceSettingsStore(appContext)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: RecognitionRepository.Listener? = null

    override fun start(listener: RecognitionRepository.Listener) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { start(listener) }
            return
        }
        this.listener = listener
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            listener.onFailure("Speech recognition is unavailable on this device.")
            return
        }
        runCatching {
            recognizer?.destroy()
            recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            ) SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            else SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer?.setRecognitionListener(recognitionListener)
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, settings.preferOfflineRecognition)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                settings.languageTag.takeIf(String::isNotBlank)?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            }
            recognizer?.startListening(request)
        }.onFailure { listener.onFailure(it.localizedMessage ?: "Could not start voice recognition.") }
    }

    override fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stop() }
            return
        }
        runCatching { recognizer?.stopListening() }
    }

    override fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { destroy() }
            return
        }
        runCatching { recognizer?.cancel(); recognizer?.destroy() }
        recognizer = null
        listener = null
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) = listener?.onListening() ?: Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "I didn’t catch that. Try saying it again."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn’t hear a command. Try again."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is needed for voice commands."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service could not finish. An offline speech model may not be installed."
                else -> "Voice recognition stopped. Please try again."
            }
            listener?.onFailure(message)
        }
        override fun onResults(results: android.os.Bundle?) {
            val transcript = SpeechResultMapper.bestTranscript(results)
            if (transcript.isBlank()) listener?.onFailure("No words were recognized. Try again.")
            else listener?.onFinalResult(transcript)
        }
        override fun onPartialResults(partialResults: android.os.Bundle?) {
            listener?.onPartialResult(SpeechResultMapper.partialTranscript(partialResults))
        }
        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }
}
