package com.example.kilo.playback.stability

import com.example.kilo.observability.PlaybackProfiler
import com.example.kilo.playbackoptimization.SourceFailureTracker
import com.example.kilo.playback.source.SourceRefreshEngine
import java.util.LinkedHashMap

/** Deduplicates wrapper recovery requests by media ID; the existing player remains authoritative. */
object RecoveryCoordinator {
    enum class State { IDLE, RECOVERING, RECOVERED, FAILED }

    private data class RecoveryRecord(
        val state: State,
        val updatedAtMs: Long,
        val queueGeneration: Long = 0L,
        val errorCategory: String = "unknown",
        val owner: String = "passive",
        val startPositionMs: Long = 0L
    )
    private const val MAX_TRACKS = 64
    private const val ACTIVE_RECOVERY_TTL_MS = 30_000L
    private val lock = Any()
    private val records = object : LinkedHashMap<String, RecoveryRecord>(MAX_TRACKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RecoveryRecord>?): Boolean =
            size > MAX_TRACKS
    }

    fun recordPlayerError(
        mediaId: String,
        errorCode: Int,
        detail: String,
        playWhenReady: Boolean = false,
        bufferedMs: Long = 0L,
        sourceValid: Boolean = false,
        existingRecoveryActive: Boolean = false,
        positionAdvancing: Boolean = false,
        queueGeneration: Long = 0L
    ): State {
        if (mediaId.isBlank()) return State.IDLE
        PlaybackProfiler.record(
            "recovery_observed", mediaId,
            "code=$errorCode playWhenReady=$playWhenReady bufferedMs=${bufferedMs.coerceAtLeast(0L)} sourceValid=$sourceValid $detail"
        )
        SourceFailureTracker.record(mediaId)
        val now = System.currentTimeMillis()
        if (existingRecoveryActive) {
            synchronized(lock) {
                records[mediaId] = RecoveryRecord(State.RECOVERING, now, queueGeneration, "unknown", "passive")
            }
            PlaybackProfiler.record("existing_recovery_active", mediaId, "wrapperRefresh=skipped")
            return State.RECOVERING
        }
        val sourceFailure = detail.contains("HTTP", ignoreCase = true) ||
            detail.contains("SOURCE", ignoreCase = true)
        if (!sourceFailure) {
            mark(mediaId, State.FAILED)
            return State.FAILED
        }
        synchronized(lock) {
            val previous = records[mediaId]
            if (previous?.state == State.RECOVERING && previous.queueGeneration == queueGeneration &&
                previous.owner == "idle_guard" &&
                now - previous.updatedAtMs < ACTIVE_RECOVERY_TTL_MS
            ) {
                PlaybackProfiler.record("recovery_duplicate_suppressed", mediaId, "state=${previous.state}")
                return State.RECOVERING
            }
            records[mediaId] = RecoveryRecord(State.RECOVERING, now, queueGeneration, "source", "wrapper")
        }
        val refreshed = SafeSourceRefreshManager.refresh(
            mediaId = mediaId,
            reason = "player_error:$errorCode",
            playWhenReady = playWhenReady,
            bufferedMs = bufferedMs,
            sourceValid = sourceValid,
            proactive = false,
            positionAdvancing = positionAdvancing
        )
        if (!refreshed) {
            mark(mediaId, State.FAILED)
            return State.IDLE
        }
        return State.RECOVERING
    }

    /** Starts one service-owned recovery transaction for this track. */
    fun beginExistingRecovery(
        mediaId: String,
        detail: String,
        queueGeneration: Long = 0L,
        baselinePositionMs: Long = 0L
    ): Boolean {
        if (mediaId.isBlank()) return false
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val previous = records[mediaId]
            if (previous?.state == State.RECOVERING && previous.queueGeneration == queueGeneration &&
                now - previous.updatedAtMs < ACTIVE_RECOVERY_TTL_MS
            ) {
                PlaybackProfiler.record(
                    "recovery_duplicate_suppressed",
                    mediaId,
                    "owner=existing_player detail=$detail"
                )
                return false
            }
            records[mediaId] = RecoveryRecord(
                State.RECOVERING, now, queueGeneration, "unexpected_idle", "idle_guard", baselinePositionMs.coerceAtLeast(0L)
            )
        }
        PlaybackProfiler.record("existing_player_recovery_started", mediaId, detail)
        return true
    }

    /** Marks that the claimed recovery attempt has been handed to the player. */
    fun markAttemptDispatched(mediaId: String, queueGeneration: Long, owner: String): Boolean {
        if (mediaId.isBlank()) return false
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val previous = records[mediaId]
            if (previous?.state != State.RECOVERING ||
                previous.queueGeneration != queueGeneration ||
                previous.owner != owner
            ) return false
            records[mediaId] = previous.copy(
                updatedAtMs = now,
                owner = "${owner}_dispatched"
            )
        }
        PlaybackProfiler.record(
            "recovery_attempt_dispatched", mediaId,
            "owner=$owner generation=$queueGeneration"
        )
        return true
    }

    /** Starts the authoritative player-error retry, even if a passive observer saw the error first. */
    fun beginPlayerErrorRecovery(
        mediaId: String,
        detail: String,
        queueGeneration: Long = 0L,
        errorCategory: String = "unknown",
        baselinePositionMs: Long = 0L
    ): Boolean {
        if (mediaId.isBlank()) return false
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val previous = records[mediaId]
            if (previous?.state == State.RECOVERING &&
                previous.queueGeneration == queueGeneration &&
                previous.errorCategory == errorCategory &&
                previous.owner == "player_error" &&
                now - previous.updatedAtMs < ACTIVE_RECOVERY_TTL_MS
            ) {
                PlaybackProfiler.record(
                    "recovery_duplicate_suppressed", mediaId,
                    "owner=player_error category=$errorCategory generation=$queueGeneration"
                )
                return false
            }
            records[mediaId] = RecoveryRecord(
                State.RECOVERING, now, queueGeneration, errorCategory, "player_error", baselinePositionMs.coerceAtLeast(0L)
            )
        }
        PlaybackProfiler.record(
            "player_error_recovery_started", mediaId,
            "category=$errorCategory generation=$queueGeneration $detail"
        )
        return true
    }

    fun cancelExistingRecovery(mediaId: String, reason: String, queueGeneration: Long? = null) {
        if (mediaId.isBlank()) return
        synchronized(lock) {
            val current = records[mediaId]
            if (queueGeneration == null || current == null || current.queueGeneration == queueGeneration) {
                records[mediaId] = RecoveryRecord(
                    State.IDLE,
                    System.currentTimeMillis(),
                    queueGeneration ?: current?.queueGeneration ?: 0L,
                    current?.errorCategory ?: "unknown",
                    "cancelled"
                )
            }
        }
        PlaybackProfiler.record("existing_player_recovery_cancelled", mediaId, reason)
    }

    /** A passive marker for the retry already owned by PlayerHolder; it queues no work. */
    fun noteExistingRecovery(mediaId: String, detail: String) {
        if (mediaId.isBlank()) return
        synchronized(lock) {
            records[mediaId] = RecoveryRecord(
                State.RECOVERING, System.currentTimeMillis(), owner = "observer"
            )
        }
        PlaybackProfiler.record("existing_player_recovery_started", mediaId, detail)
    }

    fun refreshInBackground(mediaId: String, reason: String): Boolean = SafeSourceRefreshManager.refresh(
        mediaId = mediaId,
        reason = reason,
        playWhenReady = false,
        bufferedMs = 0L,
        sourceValid = false,
        proactive = true
    )

    /** A READY callback alone is not proof of recovery; require real progress or a safe buffer. */
    fun markRecovered(mediaId: String, positionMs: Long, bufferedAheadMs: Long): Boolean {
        if (mediaId.isBlank()) return false
        val record = synchronized(lock) { records[mediaId] } ?: return false
        if (record.state != State.RECOVERING) return false
        val progressedMs = (positionMs - record.startPositionMs).coerceAtLeast(0L)
        if (progressedMs < 3_000L && bufferedAheadMs < 5_000L) {
            PlaybackProfiler.record(
                "recovery_not_yet_verified", mediaId,
                "progressedMs=$progressedMs bufferedAheadMs=${bufferedAheadMs.coerceAtLeast(0L)}"
            )
            return false
        }
        mark(mediaId, State.RECOVERED)
        PlaybackProfiler.record(
            "recovery_verified", mediaId,
            "progressedMs=$progressedMs bufferedAheadMs=${bufferedAheadMs.coerceAtLeast(0L)}"
        )
        return true
    }

    fun markFailed(mediaId: String, reason: String) {
        mark(mediaId, State.FAILED)
        PlaybackProfiler.record("recovery_failed", mediaId, reason)
    }

    fun state(mediaId: String): State = synchronized(lock) { records[mediaId]?.state ?: State.IDLE }

    private fun mark(mediaId: String, state: State) {
        if (mediaId.isBlank()) return
        synchronized(lock) {
            val current = records[mediaId]
            records[mediaId] = RecoveryRecord(
                state,
                System.currentTimeMillis(),
                current?.queueGeneration ?: 0L,
                current?.errorCategory ?: "unknown",
                if (state == State.RECOVERING) current?.owner ?: "passive" else state.name.lowercase(),
                current?.startPositionMs ?: 0L
            )
        }
    }
}
