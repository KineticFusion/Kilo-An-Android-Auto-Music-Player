package com.example.kilo

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class MoodSuggestion(val mood: String, val reason: String)

/** Uses the listener's local time and, when allowed, nearby current temperature. */
object MoodContext {
    private const val PREFS = "vybe_app_settings"
    private const val TEMP_KEY = "weather_temperature_c"
    private const val TEMP_TIME_KEY = "weather_temperature_at"
    private const val TEMP_CACHE_MS = 6L * 60L * 60L * 1000L

    fun cachedTemperatureC(context: Context): Double? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(TEMP_TIME_KEY, 0L)
        if (at <= 0L || System.currentTimeMillis() - at > TEMP_CACHE_MS) return null
        return prefs.getString(TEMP_KEY, null)?.toDoubleOrNull()
    }

    suspend fun currentTemperatureC(context: Context): Double? = withContext(Dispatchers.IO) {
        cachedTemperatureC(context)?.let { return@withContext it }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return@withContext null
        }
        val location = runCatching {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            manager.getProviders(true).asSequence().mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.filter { System.currentTimeMillis() - it.time <= 6L * 60L * 60L * 1000L }
                .maxByOrNull { it.time }
        }.getOrNull() ?: return@withContext null

        val query = Uri.Builder()
            .scheme("https")
            .authority("api.open-meteo.com")
            .appendPath("v1")
            .appendPath("forecast")
            .appendQueryParameter("latitude", location.latitude.toString())
            .appendQueryParameter("longitude", location.longitude.toString())
            .appendQueryParameter("current", "temperature_2m")
            .build()
        val connection = (URL(query.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 2500
            requestMethod = "GET"
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val temperature = JSONObject(body).optJSONObject("current")?.optDouble("temperature_2m")
                ?.takeIf { it.isFinite() }
                ?: return@withContext null
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(TEMP_KEY, temperature.toString())
                .putLong(TEMP_TIME_KEY, System.currentTimeMillis())
                .apply()
            temperature
        } finally {
            connection.disconnect()
        }
    }
}

/** Makes a recommendation only; choosing it remains an explicit user action. */
fun suggestMoodForConditions(hourOfDay: Int, temperatureC: Double?): MoodSuggestion =
    SmartMoodEngine.suggestForConditions(hourOfDay, temperatureC)

fun suggestMoodForConditions(
    hourOfDay: Int,
    temperatureC: Double?,
    avoidMoods: Set<String>,
    preferredMoods: Set<String>,
    preferredGenres: Set<String>
): MoodSuggestion = SmartMoodEngine.suggestForConditions(
    hourOfDay = hourOfDay,
    temperatureC = temperatureC,
    avoidMoods = avoidMoods,
    preferredMoods = preferredMoods,
    preferredGenres = preferredGenres
)
