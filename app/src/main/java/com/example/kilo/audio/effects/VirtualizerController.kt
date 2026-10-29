package com.example.kilo.audio.effects

import android.media.audiofx.Virtualizer
import android.os.Build

/** Uses Android's legacy virtualizer only where its API is still supported. */
class VirtualizerController {
    private var effect: Virtualizer? = null

    @Suppress("DEPRECATION")
    fun attach(sessionId: Int, strength: Int): Boolean = runCatching {
        release()
        if (Build.VERSION.SDK_INT >= 35 || sessionId <= 0 || strength <= 0) return false
        val created = Virtualizer(0, sessionId)
        effect = created
        if (created.strengthSupported) created.setStrength(strength.coerceIn(0, 150).toShort())
        created.enabled = created.strengthSupported
        created.hasControl()
    }.getOrDefault(false)

    fun release() {
        runCatching { effect?.release() }
        effect = null
    }
}
