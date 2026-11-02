package com.example.kilo.playback.crossfade

import android.content.Context

data class CrossfadeConfig(
    val enabled: Boolean,
    val durationMs: Long,
    val smart: Boolean,
    val gapless: Boolean,
    val genreAware: Boolean,
    val intelligence: Boolean,
    val preloadNext: Boolean = true
) {
    companion object {
        fun read(context: Context): CrossfadeConfig = CrossfadeConfig(
            enabled = context.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
                .getBoolean("auto_mix_enabled", false),
            durationMs = CrossfadeSettings.duration(context),
            smart = CrossfadeSettings.smartEnabled(context),
            gapless = CrossfadeSettings.gaplessEnabled(context),
            genreAware = CrossfadeSettings.genreAwareEnabled(context),
            intelligence = CrossfadeSettings.intelligenceEnabled(context)
        )
    }
}
