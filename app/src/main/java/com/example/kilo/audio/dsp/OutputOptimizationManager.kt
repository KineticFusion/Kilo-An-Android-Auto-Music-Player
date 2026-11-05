package com.example.kilo.audio.dsp

import com.example.kilo.audio.engine.AudioOutputKind
import com.example.kilo.audio.engine.AudioOutputRoute

/** Caps normalization gain by output type; it never changes Android's system volume. */
class OutputOptimizationManager {
    fun gainCeilingDb(route: AudioOutputRoute): Float = when (route.kind) {
        AudioOutputKind.CAR_BLUETOOTH, AudioOutputKind.ANDROID_AUTO -> 5.5f
        AudioOutputKind.BUILT_IN -> 4.5f
        AudioOutputKind.BLUETOOTH_SPEAKER -> 4.5f
        AudioOutputKind.EARBUDS, AudioOutputKind.HEADPHONES, AudioOutputKind.WIRED -> 4f
        AudioOutputKind.USB_DAC -> 3f
        AudioOutputKind.OTHER -> 4f
    }
}
