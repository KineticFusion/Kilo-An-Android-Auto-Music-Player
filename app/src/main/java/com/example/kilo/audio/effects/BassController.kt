package com.example.kilo.audio.effects

import android.media.audiofx.BassBoost

/** Older Android fallback only; normal profiles use the integrated DynamicsProcessing EQ. */
class BassController {
    private var effect: BassBoost? = null

    fun attach(sessionId: Int, strength: Int): Boolean = runCatching {
        release()
        if (sessionId <= 0 || strength <= 0) return false
        val created = BassBoost(0, sessionId)
        effect = created
        if (created.strengthSupported) created.setStrength(strength.coerceIn(0, 300).toShort())
        created.enabled = created.strengthSupported
        created.hasControl()
    }.getOrDefault(false)

    fun release() {
        runCatching { effect?.release() }
        effect = null
    }
}
