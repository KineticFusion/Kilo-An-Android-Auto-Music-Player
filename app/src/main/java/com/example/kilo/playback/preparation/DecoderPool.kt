package com.example.kilo.playback.preparation

/** Enforces the V3.2 memory rule: only one standby decoder may be prepared. */
class DecoderPool {
    @Volatile private var standbyTrackId: String? = null
    @Synchronized fun acquire(trackId: String): Boolean {
        val current = standbyTrackId
        if (current != null && current != trackId) return false
        standbyTrackId = trackId
        return true
    }
    @Synchronized fun release(trackId: String) { if (standbyTrackId == trackId) standbyTrackId = null }
    @Synchronized fun clear() { standbyTrackId = null }
}
