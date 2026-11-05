package com.example.kilo.audio.dsp

import android.content.SharedPreferences

enum class LoudnessMode(val title: String, val correctionLimitDb: Float) {
    OFF("Off", 0f),
    LIGHT("Light", 1.5f),
    NORMAL("Normal", 4f),
    STRONG("Strong", 4.5f);

    companion object {
        fun from(value: String?): LoudnessMode = values().firstOrNull { it.name.equals(value, true) } ?: NORMAL
    }
}

/** Persists normalization strength without changing the selected sound preset. */
class LoudnessManager(private val preferences: SharedPreferences) {
    fun mode(): LoudnessMode = LoudnessMode.from(preferences.getString(KEY_MODE, LoudnessMode.NORMAL.name))

    fun select(mode: LoudnessMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
    }

    private companion object { const val KEY_MODE = "kilo_loudness_mode" }
}
