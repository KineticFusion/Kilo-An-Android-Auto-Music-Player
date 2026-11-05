package com.example.kilo.audio.dsp

import android.content.SharedPreferences

enum class AudioPresentationMode(val title: String, val normalization: LoudnessMode) {
    STANDARD("Standard", LoudnessMode.OFF),
    BALANCED("Balanced", LoudnessMode.NORMAL),
    IMMERSIVE("Immersive", LoudnessMode.STRONG),
    AUDIOPHILE("Audiophile", LoudnessMode.LIGHT);

    companion object {
        fun from(value: String?): AudioPresentationMode =
            values().firstOrNull { it.name.equals(value, true) } ?: BALANCED
    }
}

/** Stores the user-facing listening mode; the mode maps to the bounded normalizer underneath. */
class AudioModeManager(private val preferences: SharedPreferences) {
    fun mode(): AudioPresentationMode = AudioPresentationMode.from(preferences.getString(KEY_MODE, AudioPresentationMode.BALANCED.name))

    fun select(mode: AudioPresentationMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
    }

    private companion object { const val KEY_MODE = "kilo_audio_presentation_mode" }
}
