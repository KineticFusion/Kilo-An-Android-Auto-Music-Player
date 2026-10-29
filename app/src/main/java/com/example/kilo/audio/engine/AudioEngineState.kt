package com.example.kilo.audio.engine

import com.example.kilo.audio.diagnostics.AudioAnalyzer

data class AudioEngineState(
    val output: AudioOutputRoute = AudioOutputRoute(),
    val selectedProfileId: String = "original",
    val activeProfileTitle: String = "Original",
    val effectsActive: Boolean = false,
    val limiterActive: Boolean = false,
    val analysisApplied: Boolean = false,
    val analyzer: AudioAnalyzer = AudioAnalyzer(false, false),
    val message: String = "Original tone with a level boost"
)
