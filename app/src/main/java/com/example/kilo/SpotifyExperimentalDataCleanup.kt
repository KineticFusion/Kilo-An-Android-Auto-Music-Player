package com.example.kilo

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import java.security.KeyStore

/** Clears the retired experimental Spotify web-catalog session once during the 3.4.5 upgrade. */
internal object SpotifyExperimentalDataCleanup {
    private const val MIGRATION_KEY = "spotify_catalog_removed_v345"
    private const val SESSION_PREFS = "kilo_spotify_session"
    private const val OAUTH_PREFS = "kilo_spotify_oauth"
    private const val SESSION_KEY_ALIAS = "kilo_spotify_session_cookie"

    fun clearOnce(context: Context, appPreferences: SharedPreferences) {
        if (appPreferences.getBoolean(MIGRATION_KEY, false)) return

        context.applicationContext
            .getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
        context.applicationContext
            .getSharedPreferences(OAUTH_PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()

        runCatching {
            CookieManager.getInstance().apply {
                setCookie(
                    "https://open.spotify.com",
                    "sp_dc=; Max-Age=0; Domain=.spotify.com; Path=/"
                )
                flush()
            }
        }
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(SESSION_KEY_ALIAS)
        }

        appPreferences.edit().putBoolean(MIGRATION_KEY, true).apply()
    }
}
