package com.example.kilo.audio.effects

import android.media.audiofx.LoudnessEnhancer

/** API 19+ bounded fallback when DynamicsProcessing is unavailable on a device. */
class LoudnessEnhancerSession(sessionId: Int) {
    private val effect = LoudnessEnhancer(sessionId).apply { enabled = true }

    val hasControl: Boolean get() = effect.hasControl()

    fun apply(gainDb: Float) {
        // LoudnessEnhancer takes millibels (100 mB = 1 dB). Keep headroom bounded.
        effect.setTargetGain((gainDb.coerceIn(-4f, 5.5f) * 100f).toInt())
        effect.enabled = true
    }

    fun release() = effect.release()
}
