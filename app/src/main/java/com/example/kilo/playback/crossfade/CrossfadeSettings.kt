package com.example.kilo.playback.crossfade

import android.content.Context

object CrossfadeSettings {
    const val DEFAULT_DURATION_MS = 5_000L
    val choicesMs = listOf(0L, 1_000L, 2_000L, 3_000L, 5_000L, 8_000L, 10_000L, 12_000L)
    private const val KEY_DURATION = "harmony_crossfade_duration_ms"
    private const val KEY_SMART = "harmony_smart_crossfade"
    private const val KEY_GAPLESS = "harmony_gapless_enabled"
    private const val KEY_GENRE = "harmony_genre_aware"
    private const val KEY_INTELLIGENCE = "harmony_transition_intelligence"

    fun duration(context: Context): Long = context.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
        .getLong(KEY_DURATION, DEFAULT_DURATION_MS).coerceIn(choicesMs.first(), choicesMs.last())
    fun setDuration(context: Context, value: Long) = context.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
        .edit().putLong(KEY_DURATION, value.coerceIn(choicesMs.first(), choicesMs.last())).apply()
    fun smartEnabled(context: Context): Boolean = context.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE).getBoolean(KEY_SMART, true)
    fun setSmartEnabled(context: Context, enabled: Boolean) = context.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE).edit().putBoolean(KEY_SMART, enabled).apply()
    fun gaplessEnabled(context: Context): Boolean = context.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE).getBoolean(KEY_GAPLESS, true)
    fun setGaplessEnabled(context: Context, enabled: Boolean) = context.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE).edit().putBoolean(KEY_GAPLESS, enabled).apply()
    fun genreAwareEnabled(context: Context): Boolean = context.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE).getBoolean(KEY_GENRE, true)
    fun setGenreAwareEnabled(context: Context, enabled: Boolean) = context.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE).edit().putBoolean(KEY_GENRE, enabled).apply()
    fun intelligenceEnabled(context: Context): Boolean = context.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE).getBoolean(KEY_INTELLIGENCE, true)
    fun setIntelligenceEnabled(context: Context, enabled: Boolean) = context.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE).edit().putBoolean(KEY_INTELLIGENCE, enabled).apply()
}
