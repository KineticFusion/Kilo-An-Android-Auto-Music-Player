package com.example.kilo.lyrics

import android.content.Context

object LyricsSettings {
    private const val PREFS = "vybe_app_settings"
    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("lyrics_enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("lyrics_enabled", enabled).apply()
    fun karaoke(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("lyrics_karaoke_enabled", true)
    fun setKaraoke(context: Context, enabled: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("lyrics_karaoke_enabled", enabled).apply()
    fun autoScroll(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("lyrics_auto_scroll", true)
    fun setAutoScroll(context: Context, enabled: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("lyrics_auto_scroll", enabled).apply()
    fun offlineCache(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("lyrics_offline_cache", true)
    fun setOfflineCache(context: Context, enabled: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("lyrics_offline_cache", enabled).apply()
    fun animatedHighlight(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("lyrics_animated_highlight", true)
    fun setAnimatedHighlight(context: Context, enabled: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("lyrics_animated_highlight", enabled).apply()
    fun translationEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("lyrics_translation_enabled", false)
    fun setTranslationEnabled(context: Context, enabled: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("lyrics_translation_enabled", enabled).apply()
}
