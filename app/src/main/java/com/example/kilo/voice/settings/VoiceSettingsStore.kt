package com.example.kilo.voice.settings

import android.content.Context

class VoiceSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("kilo_voice_settings", Context.MODE_PRIVATE)
    var preferOfflineRecognition: Boolean
        get() = preferences.getBoolean("prefer_offline_recognition", true)
        set(value) { preferences.edit().putBoolean("prefer_offline_recognition", value).apply() }
    var languageTag: String
        get() = preferences.getString("language_tag", "")?.orEmpty().orEmpty()
        set(value) { preferences.edit().putString("language_tag", value.take(32)).apply() }
}
