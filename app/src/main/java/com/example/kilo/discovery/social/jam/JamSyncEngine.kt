package com.example.kilo.discovery.social.jam

import com.example.kilo.discovery.JamSession

/** Transport seam for a future authenticated sync service; no cloud endpoint is configured in this app. */
interface JamSyncTransport {
    suspend fun publish(session: JamSession): Result<String>
    suspend fun join(shareCode: String): Result<JamSession>
}

class JamSyncEngine(private val transport: JamSyncTransport? = null) {
    val isConfigured: Boolean get() = transport != null
    suspend fun publish(session: JamSession): Result<String> = transport?.publish(session)
        ?: Result.failure(IllegalStateException("Jam sharing service is not configured"))
    suspend fun join(code: String): Result<JamSession> = transport?.join(code)
        ?: Result.failure(IllegalStateException("Jam sharing service is not configured"))
}
