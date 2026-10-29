package com.example.kilo

import android.content.Context
import android.os.Handler
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.example.kilo.androidauto.performance.AndroidAutoPerformanceMode
import com.example.kilo.networkmonitor.NetworkHealthMonitor
import com.example.kilo.playbackrecovery.PlaybackDiagnostics
import com.example.kilo.playback.stability.RecoveryCoordinator
import com.example.kilo.playback.stability.MediaSourceProtectionLayer
import com.example.kilo.playback.stability.PlaybackHealthSnapshot
import com.example.kilo.playback.stability.PlaybackStabilityManager
import com.example.kilo.playback.stability.RecoveryProtectionLayer
import com.example.kilo.playback.stability.MediaSourceAudit
import com.example.kilo.playbackoptimization.SourceLifecycleManager

/**
 * Read-only observer around KILO's shared player. It is attached to the same player used by
 * phone and Android Auto; it issues no player commands and does not alter the media session.
 */
@UnstableApi
internal class PlaybackStabilityTracer(context: Context) : Player.Listener, AnalyticsListener {
    private val appContext = context.applicationContext
    @Volatile private var player: Player? = null
    @Volatile private var previousState = Player.STATE_IDLE
    @Volatile private var samplerHandler: Handler? = null
    @Volatile private var timelineRevision = 0L
    @Volatile private var lastDecoderName = ""
    @Volatile private var decoderFaulted = false
    private val sampler = object : Runnable {
        override fun run() {
            val active = player ?: return
            captureHealth(active)
            samplerHandler?.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    fun attach(target: Player) {
        detach()
        player = target
        previousState = target.playbackState
        timelineRevision = 0L
        decoderFaulted = false
        samplerHandler = Handler(target.applicationLooper).also { it.post(sampler) }
    }

    fun detach() {
        samplerHandler?.removeCallbacks(sampler)
        samplerHandler = null
        player = null
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val id = mediaItem?.mediaId.orEmpty()
        val active = player
        MediaSourceAudit.observed(
            "media_item_transition",
            id,
            active?.currentPosition ?: 0L,
            active?.playbackState ?: Player.STATE_IDLE,
            "reason=$reason queueIndex=${active?.currentMediaItemIndex ?: -1} " +
                "queueCount=${active?.mediaItemCount ?: -1} thread=${Thread.currentThread().name}"
        )
        PlaybackDiagnostics.record(
            "stability_track_transition", id,
            "reason=$reason carMode=${carMode()} queueIndex=${player?.currentMediaItemIndex ?: -1} " +
                "queueCount=${player?.mediaItemCount ?: -1}"
        )
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val active = player ?: return
        val id = active.currentMediaItem?.mediaId.orEmpty()
        val old = previousState
        val previousHealth = PlaybackStabilityManager.latest(id)
        previousState = playbackState
        val bufferedMs = (active.bufferedPosition - active.currentPosition).coerceAtLeast(0L)
        val sourceValid = id.isNotBlank() && sourceIsValid(id, active.currentMediaItem)
        val sourceState = if (id.isBlank()) "UNKNOWN" else SourceLifecycleManager.state(id).name
        val stateName = stateName(playbackState)
        val currentHealth = healthSnapshot(active, bufferedMs, sourceValid, sourceState)
        PlaybackStabilityManager.record(currentHealth)

        if (old != playbackState) {
            PlaybackDiagnostics.record(
                "stability_state", id,
                "from=${stateName(old)} to=$stateName positionMs=${active.currentPosition} " +
                    "bufferedMs=$bufferedMs playWhenReady=${active.playWhenReady} isPlaying=${active.isPlaying} " +
                    "sourceState=$sourceState sourceValid=$sourceValid recovery=${RecoveryCoordinator.state(id)} " +
                    "queueIndex=${active.currentMediaItemIndex} queueCount=${active.mediaItemCount} " +
                    "network=${NetworkHealthMonitor.snapshot().transport}/${NetworkHealthMonitor.snapshot().grade} " +
                    "carMode=${carMode()} uri=${safeUri(active.currentMediaItem)}"
            )
        }

        if (old == Player.STATE_READY && playbackState == Player.STATE_IDLE) {
            val transition = PlaybackStabilityManager.detectIdleTransition(
                old, playbackState, previousHealth, currentHealth
            )
            val cause = transition?.rootCause
            val protection = transition?.protection
            val recentHistory = PlaybackStabilityManager.recent().takeLast(5).joinToString(";") { snapshotSummary(it) }
            PlaybackDiagnostics.record(
                "ready_to_idle", id,
                "positionMs=${active.currentPosition} bufferedMs=$bufferedMs playWhenReady=${active.playWhenReady} " +
                    "isPlaying=${active.isPlaying} sourceState=$sourceState sourceValid=$sourceValid " +
                    "recovery=${RecoveryCoordinator.state(id)} queueIndex=${active.currentMediaItemIndex} " +
                    "network=${NetworkHealthMonitor.snapshot().grade} carMode=${carMode()} " +
                    "rootCause=${cause ?: "UNKNOWN_IDLE"} recoveryProtection=${protection?.shouldBlock == true} " +
                    "protectionReason=${protection?.reason.orEmpty()}"
            )
            PlaybackDiagnostics.record(
                "idle_root_cause", id,
                "cause=${cause ?: "UNKNOWN_IDLE"} recoveryProtection=${protection?.shouldBlock == true} " +
                    "protectionReason=${protection?.reason.orEmpty()} previous=${snapshotSummary(previousHealth)} " +
                    "current=${snapshotSummary(currentHealth)} recent=[$recentHistory]"
            )
            PlaybackPerformanceDashboard.logSnapshot("ready_to_idle:${cause ?: "UNKNOWN_IDLE"}")
        } else if (old == Player.STATE_BUFFERING && playbackState == Player.STATE_IDLE) {
            val transition = PlaybackStabilityManager.detectIdleTransition(
                old, playbackState, previousHealth, currentHealth
            )
            val cause = transition?.rootCause
            val protection = transition?.protection
            val recentHistory = PlaybackStabilityManager.recent().takeLast(5).joinToString(";") { snapshotSummary(it) }
            PlaybackDiagnostics.record(
                "buffering_to_idle", id,
                "positionMs=${active.currentPosition} bufferedMs=$bufferedMs playWhenReady=${active.playWhenReady} " +
                    "sourceState=$sourceState sourceValid=$sourceValid recovery=${RecoveryCoordinator.state(id)} " +
                    "network=${NetworkHealthMonitor.snapshot().grade} carMode=${carMode()} " +
                    "rootCause=${cause ?: "UNKNOWN_IDLE"} recoveryProtection=${protection?.shouldBlock == true} " +
                    "protectionReason=${protection?.reason.orEmpty()}"
            )
            PlaybackDiagnostics.record(
                "idle_root_cause", id,
                "cause=${cause ?: "UNKNOWN_IDLE"} recoveryProtection=${protection?.shouldBlock == true} " +
                    "protectionReason=${protection?.reason.orEmpty()} previous=${snapshotSummary(previousHealth)} " +
                    "current=${snapshotSummary(currentHealth)} recent=[$recentHistory]"
            )
            PlaybackPerformanceDashboard.logSnapshot("buffering_to_idle:${cause ?: "UNKNOWN_IDLE"}")
        }

        if (playbackState == Player.STATE_READY && active.playWhenReady) {
            RecoveryCoordinator.markRecovered(id)
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val active = player ?: return
        val id = active.currentMediaItem?.mediaId.orEmpty()
        if (error.errorCodeName.contains("DECODER", ignoreCase = true)) decoderFaulted = true
        val bufferedMs = (active.bufferedPosition - active.currentPosition).coerceAtLeast(0L)
        val sourceValid = id.isNotBlank() && sourceIsValid(id, active.currentMediaItem)
        PlaybackDiagnostics.record(
            "stability_player_error", id,
            "code=${error.errorCode} name=${error.errorCodeName} positionMs=${active.currentPosition} " +
                "bufferedMs=$bufferedMs playWhenReady=${active.playWhenReady} sourceValid=$sourceValid " +
                "sourceState=${if (id.isBlank()) "UNKNOWN" else SourceLifecycleManager.state(id).name} " +
                "recovery=${RecoveryCoordinator.state(id)} carMode=${carMode()}"
        )
        MediaSourceAudit.observed(
            "player_error", id, active.currentPosition, active.playbackState,
            "code=${error.errorCode} name=${error.errorCodeName} thread=${Thread.currentThread().name}"
        )
        PlaybackPerformanceDashboard.logSnapshot("player_error:${error.errorCodeName}")
    }

    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
        val active = player ?: return
        timelineRevision++
        val id = active.currentMediaItem?.mediaId.orEmpty()
        MediaSourceProtectionLayer.observeTimelineChange(active, reason)
        PlaybackDiagnostics.record(
            "media_source_timeline_change", id,
            "reason=$reason windows=${timeline.windowCount} periods=${timeline.periodCount} " +
                "positionMs=${active.currentPosition} state=${stateName(active.playbackState)} " +
                "carMode=${carMode()}"
        )
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        lastDecoderName = decoderName
        decoderFaulted = false
        val id = player?.currentMediaItem?.mediaId.orEmpty()
        PlaybackDiagnostics.record(
            "decoder_initialized", id,
            "trackType=audio decoder=$decoderName initializedAtMs=$initializedTimestampMs " +
                "initializationMs=$initializationDurationMs carMode=${carMode()}"
        )
        MediaSourceAudit.observed(
            "audio_decoder_initialized", id, player?.currentPosition ?: 0L,
            player?.playbackState ?: Player.STATE_IDLE,
            "decoder=$decoderName initializationMs=$initializationDurationMs"
        )
    }

    fun reportErrorToRecoveryCoordinator(mediaId: String, code: Int, name: String) {
        val active = player
        val matchesCurrent = active?.currentMediaItem?.mediaId == mediaId
        val bufferedMs = if (matchesCurrent) {
            (active!!.bufferedPosition - active.currentPosition).coerceAtLeast(0L)
        } else 0L
        val sourceValid = sourceIsValid(mediaId, active?.currentMediaItem?.takeIf { matchesCurrent })
        val positionAdvancing = if (matchesCurrent) {
            val current = healthSnapshot(
                active!!,
                bufferedMs,
                sourceValid,
                SourceLifecycleManager.state(mediaId).name
            )
            RecoveryProtectionLayer.isHealthyProgress(PlaybackStabilityManager.latest(mediaId), current)
        } else false
        RecoveryCoordinator.recordPlayerError(
            mediaId = mediaId,
            errorCode = code,
            detail = name,
            playWhenReady = matchesCurrent && active!!.playWhenReady,
            bufferedMs = bufferedMs,
            sourceValid = sourceValid,
            existingRecoveryActive = runCatching { PlayerHolder.isRetrying(mediaId) }.getOrDefault(false),
            positionAdvancing = positionAdvancing
        )
    }

    private fun captureHealth(active: Player) {
        val id = active.currentMediaItem?.mediaId.orEmpty()
        if (id.isBlank()) return
        val sourceValid = sourceIsValid(id, active.currentMediaItem)
        val sourceState = SourceLifecycleManager.state(id).name
        val bufferedMs = (active.bufferedPosition - active.currentPosition).coerceAtLeast(0L)
        PlaybackStabilityManager.record(healthSnapshot(active, bufferedMs, sourceValid, sourceState))
    }

    private fun healthSnapshot(
        active: Player,
        bufferedMs: Long,
        sourceValid: Boolean,
        sourceState: String
    ): PlaybackHealthSnapshot {
        val network = NetworkHealthMonitor.snapshot()
        val timeline = active.currentTimeline
        return PlaybackHealthSnapshot(
            mediaId = active.currentMediaItem?.mediaId.orEmpty(),
            elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
            positionMs = active.currentPosition,
            bufferedMs = bufferedMs,
            playerState = active.playbackState,
            playWhenReady = active.playWhenReady,
            isPlaying = active.isPlaying,
            sourceValid = sourceValid,
            sourceState = sourceState,
            recoveryState = RecoveryCoordinator.state(active.currentMediaItem?.mediaId.orEmpty()).name,
            queueIndex = active.currentMediaItemIndex,
            networkState = network.grade.toString(),
            bandwidthKbps = network.downstreamKbps.toLong().coerceAtLeast(0L),
            timelineWindows = timeline.windowCount,
            timelinePeriods = timeline.periodCount,
            timelineRevision = timelineRevision,
            activeMediaSource = mediaSourceFingerprint(active.currentMediaItem),
            decoderName = lastDecoderName,
            decoderFaulted = decoderFaulted
        )
    }

    private fun snapshotSummary(snapshot: PlaybackHealthSnapshot?): String = snapshot?.let {
        "t=${it.elapsedRealtimeMs},state=${stateName(it.playerState)},pos=${it.positionMs},buf=${it.bufferedMs}," +
            "pwr=${it.playWhenReady},playing=${it.isPlaying},sourceValid=${it.sourceValid},source=${it.sourceState}," +
            "recovery=${it.recoveryState},q=${it.queueIndex},net=${it.networkState},bw=${it.bandwidthKbps}," +
            "timeline=${it.timelineWindows}/${it.timelinePeriods}@${it.timelineRevision}," +
            "sourceHash=${it.activeMediaSource},decoder=${it.decoderName},decoderFault=${it.decoderFaulted}"
    } ?: "none"

    /** Hash the URI for comparisons without exposing signed stream URLs in logs or reports. */
    private fun mediaSourceFingerprint(item: MediaItem?): String = runCatching {
        val uri = item?.localConfiguration?.uri?.toString().orEmpty()
        if (uri.isBlank()) "" else Integer.toHexString(uri.hashCode())
    }.getOrDefault("")

    private fun sourceIsValid(mediaId: String, item: MediaItem?): Boolean {
        if (mediaId.isBlank()) return false
        if (ResolvedTrackCache.peek(mediaId) != null) return true
        val scheme = runCatching { item?.localConfiguration?.uri?.scheme?.lowercase() }.getOrNull()
        return scheme in setOf("http", "https", "content", "file", "android.resource")
    }

    private fun carMode(): Boolean = runCatching {
        AndroidAutoPerformanceMode.enabled(appContext)
    }.getOrDefault(false)

    private fun safeUri(item: MediaItem?): String {
        val uri = item?.localConfiguration?.uri ?: return "none"
        return runCatching {
            val parsed = Uri.parse(uri.toString())
            "${parsed.scheme.orEmpty()}://${parsed.host.orEmpty()}"
        }.getOrDefault("invalid")
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 250L
    }
}
