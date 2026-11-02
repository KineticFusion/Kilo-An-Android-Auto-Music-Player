package com.example.kilo.lyrics.karaoke

import android.util.Log

object KaraokeMetrics {
    fun sync(trackId: String, deltaMs: Long) = Log.d("KiloKaraoke", "sync track=${trackId.hashCode()} deltaMs=$deltaMs")
}
