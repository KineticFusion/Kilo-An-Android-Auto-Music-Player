package com.example.kilo.playback.prepared

import com.example.kilo.playbackoptimization.PreparedTrack as LegacyPreparedTrack
import com.example.kilo.playbackoptimization.PreparedTrackRecord
import com.example.kilo.playbackoptimization.PreparedTrackRegistry as LegacyRegistry
import com.example.kilo.playbackoptimization.PreparedTrackStatus
import com.example.kilo.playbackoptimization.PreparedTrackStore as LegacyStore
import com.example.kilo.playbackoptimization.SourceHealthValidator
import com.example.kilo.playbackoptimization.WarmedSourceMetadata
import com.example.kilo.streaming.StreamInfo

/** Unified API backed by KILO's existing bounded registry so there is one source of truth. */
object PreparedTrackRegistry {
    fun register(track: PreparedPlayableTrack) {
        val item = track.mediaItem ?: return
        if (track.trackId.isBlank() || item.mediaId != track.trackId) return
        LegacyRegistry.register(
            LegacyPreparedTrack(
                mediaId = track.trackId,
                title = track.title,
                artist = track.artist,
                album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
                durationMs = track.durationMs,
                artworkUri = track.artwork,
                mediaItem = item
            )
        )
        track.streamInfo?.let { stream ->
            LegacyRegistry.markReady(track.trackId, stream.toWarmedSource())
        }
    }

    fun get(trackId: String): PreparedPlayableTrack? =
        LegacyStore.get(trackId)?.let(::fromRecord)

    fun snapshot(): List<PreparedPlayableTrack> = LegacyStore.snapshot().map(::fromRecord)

    fun markPreparing(trackId: String) = LegacyRegistry.markPreparing(trackId)
    fun markPlaying(trackId: String) = LegacyRegistry.markPlaying(trackId)
    fun markExpiring(trackId: String) = LegacyRegistry.markExpiring(trackId)
    fun markRefreshing(trackId: String) = LegacyRegistry.markRefreshing(trackId)
    fun markFailed(trackId: String) = LegacyRegistry.markFailed(trackId)

    fun readyMediaItem(trackId: String) = get(trackId)?.takeIf { it.isReady() }?.mediaItem

    private fun fromRecord(record: PreparedTrackRecord): PreparedPlayableTrack {
        val now = System.currentTimeMillis()
        val stream = record.streamUrl?.takeIf(String::isNotBlank)?.let { url ->
            com.example.kilo.streaming.StreamInfo(
                url = url,
                codec = record.format,
                userAgent = record.headers["User-Agent"].orEmpty(),
                requestHeaders = record.headers,
                bitrateKbps = record.bitrateKbps,
                durationMs = record.durationMs,
                expiresAtEpochMs = record.expiryTimestampMs
            )
        }
        val sourceValid = stream?.let { info ->
            val uri = runCatching { java.net.URI(info.url) }.getOrNull()
            uri?.scheme?.lowercase() in setOf("http", "https") &&
                !uri?.host.isNullOrBlank() &&
                (info.expiresAtEpochMs <= 0L || info.expiresAtEpochMs > now)
        } == true
        val health = when {
            sourceValid -> PreparedTrackHealth.VALID
            record.status == PreparedTrackStatus.EXPIRING || record.expiryTimestampMs in 1..now -> PreparedTrackHealth.EXPIRING
            record.status == PreparedTrackStatus.FAILED -> PreparedTrackHealth.INVALID
            else -> PreparedTrackHealth.UNKNOWN
        }
        val state = when (record.status) {
            PreparedTrackStatus.PREPARING -> PreparedTrackState.PREPARING
            PreparedTrackStatus.READY -> PreparedTrackState.READY
            PreparedTrackStatus.PLAYING -> PreparedTrackState.PLAYING
            PreparedTrackStatus.EXPIRING -> PreparedTrackState.EXPIRING
            PreparedTrackStatus.REFRESHING -> PreparedTrackState.REFRESHING
            PreparedTrackStatus.FAILED -> PreparedTrackState.FAILED
        }
        return PreparedPlayableTrack(
            trackId = record.trackId,
            title = record.mediaItem?.mediaMetadata?.title?.toString().orEmpty(),
            artist = record.mediaItem?.mediaMetadata?.artist?.toString().orEmpty(),
            artwork = record.artwork,
            durationMs = record.durationMs,
            mediaItem = record.mediaItem,
            streamInfo = stream?.takeIf { sourceValid },
            preparedTimestampMs = record.validationTimeMs.takeIf { it > 0L } ?: record.updatedAtMs,
            expiryTimestampMs = record.expiryTimestampMs,
            healthStatus = health,
            preparedState = state
        )
    }
}

private fun StreamInfo.toWarmedSource(): WarmedSourceMetadata? = WarmedSourceMetadata(
    streamUrl = url,
    requestHeaders = requestHeaders,
    codec = codec,
    bitrateKbps = bitrateKbps,
    durationMs = durationMs,
    sourceExpiresAtEpochMs = expiresAtEpochMs
).takeIf { SourceHealthValidator.isUsable(it) }
