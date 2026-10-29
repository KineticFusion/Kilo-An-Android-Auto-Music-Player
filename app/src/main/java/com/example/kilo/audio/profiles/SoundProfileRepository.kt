package com.example.kilo.audio.profiles

import android.content.SharedPreferences
import com.example.kilo.audio.engine.AudioOutputKind
import com.example.kilo.audio.engine.AudioOutputRoute

/** Preserves the sound-profile choices while keeping Original flat and level-boosted. */
class SoundProfileRepository(private val preferences: SharedPreferences) {
    companion object {
        const val ORIGINAL = "original"
        private val flat = List(10) { 0f }
        val profiles = listOf(
            SoundProfile(ORIGINAL, "Original", "Original tone with a level boost", flat, usesLimiter = true),
            SoundProfile("balanced", "Balanced", "A clear, even presentation", flat),
            SoundProfile("audiophile", "Audiophile", "Subtle detail with minimal coloration", listOf(0.2f, 0.3f, 0.2f, 0f, 0f, 0.1f, 0.2f, 0.3f, 0.2f, 0.1f)),
            SoundProfile("car_concert", "Car Concert", "Fuller low end and stronger perceived loudness for a car", listOf(1.3f, 2.2f, 2.0f, 0.8f, 0f, 0.4f, 0.9f, 1.5f, 0.9f, 0.4f), 4.0f, 120),
            SoundProfile("car_concert_extreme", "Car Concert Extreme", "More impact and loudness, with peak limiting", listOf(1.8f, 2.7f, 2.4f, 1.0f, 0f, 0.4f, 1.0f, 1.7f, 1.0f, 0.5f), 5.0f, 150),
            SoundProfile("night_drive", "Night Drive", "Warm bass with a relaxed top end", listOf(1.0f, 1.4f, 1.1f, 0.3f, 0f, 0.1f, 0.3f, 0.5f, 0.2f, -0.2f), 2.5f, 90),
            SoundProfile("edm_festival", "EDM Festival", "Punchy low end and extra presence", listOf(1.2f, 2.0f, 1.6f, 0.4f, -0.2f, 0f, 0.5f, 1.1f, 0.8f, 0.3f), 3.0f, 110),
            SoundProfile("hip_hop_beast", "Hip-Hop Beast", "Tighter kick and bass weight", listOf(1.7f, 2.5f, 1.7f, 0.3f, -0.2f, 0f, 0.2f, 0.8f, 0.4f, 0f), 3.0f, 70),
            SoundProfile("vocal_focus", "Vocal Focus", "Keeps voices forward", listOf(-0.5f, -0.2f, 0f, 0.3f, 0.6f, 1.0f, 1.2f, 0.9f, 0.4f, 0f)),
            SoundProfile("movie_mode", "Movie Mode", "A broader presentation for spoken content and soundtracks", listOf(0.3f, 0.5f, 0.2f, 0f, 0.1f, 0.4f, 0.8f, 0.8f, 0.4f, 0.1f), 0.3f, 100),
            SoundProfile("headphones", "Headphones", "Gentle presence without heavy bass boost", listOf(0f, 0.2f, 0.3f, 0.1f, 0f, 0.1f, 0.3f, 0.4f, 0.2f, 0f)),
            SoundProfile("earbuds", "Earbuds", "A small clarity lift for compact drivers", listOf(0.2f, 0.4f, 0.3f, 0f, -0.1f, 0.1f, 0.4f, 0.5f, 0.2f, 0f)),
            SoundProfile("speakers", "Bluetooth Speakers", "Adds a little body and stereo presence", listOf(0.3f, 0.7f, 0.5f, 0.1f, 0f, 0.1f, 0.3f, 0.6f, 0.4f, 0.1f), 0.3f, 80)
        )
    }

    fun selectedId(): String = preferences.getString("kilo_sound_profile", ORIGINAL).orEmpty().ifBlank { ORIGINAL }

    fun select(id: String) {
        if (profiles.any { it.id == id }) preferences.edit().putString("kilo_sound_profile", id).apply()
    }

    fun forOutput(route: AudioOutputRoute): SoundProfile {
        val selected = profiles.firstOrNull { it.id == selectedId() } ?: profiles.first()
        if (selected.id != ORIGINAL) return selected
        val routeGain = when (route.kind) {
            AudioOutputKind.CAR_BLUETOOTH, AudioOutputKind.ANDROID_AUTO -> 4.0f
            AudioOutputKind.BLUETOOTH_SPEAKER -> 2.5f
            AudioOutputKind.EARBUDS, AudioOutputKind.HEADPHONES, AudioOutputKind.WIRED, AudioOutputKind.USB_DAC -> 2.0f
            AudioOutputKind.BUILT_IN -> 1.0f
            AudioOutputKind.OTHER -> 2.0f
        }
        return selected.copy(inputGainDb = routeGain, virtualizerStrength = 0, gainsDb = flat)
    }
}
