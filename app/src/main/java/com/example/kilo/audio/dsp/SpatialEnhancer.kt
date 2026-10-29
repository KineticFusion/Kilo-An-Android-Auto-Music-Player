package com.example.kilo.audio.dsp

import android.content.Context
import android.media.AudioManager
import android.os.Build

class SpatialEnhancer(context: Context) {
    val platformSpatializerAvailable: Boolean = if (Build.VERSION.SDK_INT >= 32) {
        runCatching {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val spatializer = AudioManager::class.java.getMethod("getSpatializer").invoke(manager)
            spatializer.javaClass.getMethod("isAvailable").invoke(spatializer) as? Boolean ?: false
        }.getOrDefault(false)
    } else false
}
