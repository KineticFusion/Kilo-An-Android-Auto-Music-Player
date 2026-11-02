package com.example.kilo.playback.crossfade

import android.util.Log
import androidx.media3.common.Player

/** A failed standby deck is discarded; the active/session deck continues untouched. */
class CrossfadeRecovery(private val primary: Player, private val secondary: Player, private val mixer: AudioMixer) {
    fun standbyFailed(trackId: String, error: Throwable?, primaryGain: Float) {
        Log.w("KiloCrossfade", "standby_prepare_failed id=$trackId; retaining active deck", error)
        secondary.pause()
        secondary.clearMediaItems()
        secondary.volume = 0f
        mixer.restorePrimary(primaryGain)
    }
}
