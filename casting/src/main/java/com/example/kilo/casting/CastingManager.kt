package com.example.kilo.casting

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

data class CastDevice(val id: String, val name: String, val kind: String = "Receiver")
data class CastTrack(
    val id: String,
    val title: String,
    val artist: String,
    val streamUrl: String,
    val artworkUrl: String = "",
    val durationMs: Long = 0L
)
data class CastPlaybackState(
    val connectedDevice: CastDevice? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 1f,
    val trackId: String? = null
)

/** Google Cast/receiver SDK boundary; the host supplies the configured platform implementation. */
interface CastingTransport {
    fun discover(onDevices: (List<CastDevice>) -> Unit)
    fun connect(device: CastDevice, onResult: (Result<Unit>) -> Unit)
    fun disconnect()
    fun loadQueue(queue: List<CastTrack>, startIndex: Int, startPositionMs: Long, autoPlay: Boolean)
    fun play()
    fun pause()
    fun seek(positionMs: Long)
    fun next()
    fun previous()
    fun setVolume(volume: Float)
    fun addStateListener(listener: (CastPlaybackState) -> Unit)
}

/**
 * Cast session facade. It owns remote state and queue synchronization only; it never creates
 * or mutates KILO's local ExoPlayer. With no transport installed, local playback is untouched.
 */
class CastingManager(private val executor: Executor) {
    private val transport = AtomicReference<CastingTransport?>(null)
    @Volatile private var currentState = CastPlaybackState()
    @Volatile private var stateListener: ((CastPlaybackState) -> Unit)? = null

    fun installTransport(value: CastingTransport) {
        transport.set(value)
        value.addStateListener { state ->
            currentState = state.copy(positionMs = state.positionMs.coerceAtLeast(0L), volume = state.volume.coerceIn(0f, 1f))
            stateListener?.invoke(currentState)
        }
    }

    fun state(): CastPlaybackState = currentState
    fun observe(listener: (CastPlaybackState) -> Unit) { stateListener = listener; listener(currentState) }

    fun discover(onDevices: (List<CastDevice>) -> Unit) {
        val active = transport.get() ?: return onDevices(emptyList())
        executor.execute { runCatching { active.discover(onDevices) }.onFailure { onDevices(emptyList()) } }
    }

    fun connect(
        device: CastDevice,
        queue: List<CastTrack>,
        startIndex: Int,
        positionMs: Long,
        onResult: (Result<Unit>) -> Unit
    ) {
        val active = transport.get()
            ?: return onResult(Result.failure(IllegalStateException("Casting is unavailable until a receiver adapter is configured.")))
        if (queue.isEmpty() || queue.any { it.streamUrl.isBlank() }) {
            onResult(Result.failure(IllegalArgumentException("The cast queue must contain playable stream URLs.")))
            return
        }
        executor.execute {
            active.connect(device) { result ->
                result.onSuccess {
                    runCatching {
                        active.loadQueue(queue.toList(), startIndex.coerceIn(queue.indices), positionMs.coerceAtLeast(0L), true)
                    }.onFailure { error -> onResult(Result.failure(error)); return@onSuccess }
                    onResult(Result.success(Unit))
                }.onFailure { error -> onResult(Result.failure(error)) }
            }
        }
    }

    fun disconnect() = transport.get()?.disconnect()
    fun play() = transport.get()?.play()
    fun pause() = transport.get()?.pause()
    fun seek(positionMs: Long) = transport.get()?.seek(positionMs.coerceAtLeast(0L))
    fun next() = transport.get()?.next()
    fun previous() = transport.get()?.previous()
    fun setVolume(volume: Float) = transport.get()?.setVolume(volume.coerceIn(0f, 1f))
}
