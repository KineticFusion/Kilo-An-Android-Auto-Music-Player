package com.example.kilo.audio.diagnostics

/** Reports supported session-effect capabilities; it does not inspect decoded PCM. */
data class AudioAnalyzer(
    val platformDynamicsAvailable: Boolean,
    val virtualizerAvailable: Boolean,
    val harmonicExciterAvailable: Boolean = false,
    val pcmClippingMeasurementAvailable: Boolean = false
)
