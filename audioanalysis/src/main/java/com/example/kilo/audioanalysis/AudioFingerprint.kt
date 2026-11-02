package com.example.kilo.audioanalysis

import java.security.MessageDigest

object AudioFingerprint {
    fun fromTrackId(trackId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(trackId.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}
