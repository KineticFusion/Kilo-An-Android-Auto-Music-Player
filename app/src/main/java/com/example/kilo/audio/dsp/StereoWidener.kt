package com.example.kilo.audio.dsp

import com.example.kilo.audio.profiles.SoundProfile

/** Maps width preference to Android's optional virtualizer, capped to a mild setting. */
class StereoWidener {
    fun virtualizerStrength(profile: SoundProfile, carRoute: Boolean): Int = profile.virtualizerStrength.coerceIn(0, if (carRoute) 150 else 100)
}
