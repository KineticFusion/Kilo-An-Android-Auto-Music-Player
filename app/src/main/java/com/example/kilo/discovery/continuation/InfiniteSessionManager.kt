package com.example.kilo.discovery.continuation

import com.example.kilo.SongItemClean
import com.example.kilo.discovery.discoveryKey

/** Session-level guard prevents duplicate appends when Media3 reports adjacent transitions. */
class InfiniteSessionManager {
    private var sessionSignature: String = ""
    private val appended = linkedSetOf<String>()
    private var requestInFlight = false

    @Synchronized fun begin(playlist: List<SongItemClean>) {
        val signature = playlist.take(3).joinToString("|") { discoveryKey(it) }
        if (signature != sessionSignature) {
            sessionSignature = signature
            appended.clear()
            requestInFlight = false
        }
    }

    @Synchronized fun tryBeginAppend(playlist: List<SongItemClean>): Boolean {
        begin(playlist)
        if (requestInFlight) return false
        requestInFlight = true
        return true
    }

    @Synchronized fun finishAppend(songs: List<SongItemClean>) {
        songs.forEach { appended += discoveryKey(it) }
        requestInFlight = false
    }

    @Synchronized fun failAppend() { requestInFlight = false }
    @Synchronized fun appendedIds(): Set<String> = appended.toSet()
}
