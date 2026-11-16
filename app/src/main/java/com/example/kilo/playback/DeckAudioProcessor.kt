package com.example.kilo.playback

import android.media.AudioFormat
import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 AudioProcessor that feeds decoded PCM audio into the Native C++ Crossfade Engine.
 *
 * This replaces the standard AudioSink - ExoPlayer decodes to PCM and calls queueInput(),
 * we push the raw float32 frames directly into the native ring buffer via JNI.
 *
 * Supports backpressure: if native buffer >80% full, we signal ExoPlayer to slow down.
 */
@UnstableApi
class DeckAudioProcessor(
    private val deckId: Int, // 0 = Deck A, 1 = Deck B
    private val onBackpressureChange: (Boolean) -> Unit = { }
) : AudioProcessor {

    companion object {
        private const val TAG = "DeckAudioProcessor"
        private const val INPUT_BUFFER_SIZE = 8192 // frames
        const val DECK_A = 0
        const val DECK_B = 1
    }

    // Internal state
    private var active = false
    private var inputEnded = false
    private var pendingInputBuffer: ByteBuffer? = null
    private var pendingInputFrames = 0

    // Reusable float array for JNI (avoids allocation in hot path)
    private val floatBuffer = FloatArray(INPUT_BUFFER_SIZE * NativeAudioEngine.INTERNAL_CHANNEL_COUNT)

    // Backpressure monitoring
    private var backpressureActive = false
    private val scope = CoroutineScope(Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // We accept any input format, output is always 48kHz stereo float32
        // ExoPlayer will handle resampling/channel conversion before calling queueInput
        val outputFormat = AudioProcessor.AudioFormat(
            NativeAudioEngine.INTERNAL_SAMPLE_RATE,
            NativeAudioEngine.INTERNAL_CHANNEL_COUNT,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        Log.d(TAG, "Deck $deckId configured: ${inputAudioFormat.sampleRate}Hz -> ${outputFormat.sampleRate}Hz float32")
        return outputFormat
    }

    override fun isActive(): Boolean = active

    // Note: setActive is not available in Media3 1.4.1 AudioProcessor interface
    // We manage active state internally via configure() and reset()
    internal fun setActiveInternal(active: Boolean) {
        this.active = active
        if (active) {
            inputEnded = false
            startBackpressureMonitor()
        } else {
            stopBackpressureMonitor()
            flushPending()
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!active) return

        // Convert ByteBuffer (int16 or float32) to float32 array for native
        val remainingBytes = inputBuffer.remaining()
        if (remainingBytes == 0) return

        val framesAvailable = remainingBytes / (2 * NativeAudioEngine.INTERNAL_CHANNEL_COUNT) // Assume 16-bit for safety
        val framesToProcess = minOf(framesAvailable, INPUT_BUFFER_SIZE)

        inputBuffer.order(ByteOrder.nativeOrder())

        // Check if input is float32 (4 bytes per sample) or int16 (2 bytes per sample)
        // Media3 typically provides float32 after the default processing chain
        val isFloatInput = (remainingBytes % (4 * NativeAudioEngine.INTERNAL_CHANNEL_COUNT) == 0)

        if (isFloatInput) {
            // Already float32 - copy directly
            inputBuffer.asFloatBuffer().get(floatBuffer, 0, framesToProcess * NativeAudioEngine.INTERNAL_CHANNEL_COUNT)
        } else {
            // Convert int16 to float32 (-32768..32767 -> -1.0..1.0)
            val shortBuffer = inputBuffer.asShortBuffer()
            for (i in 0 until framesToProcess * NativeAudioEngine.INTERNAL_CHANNEL_COUNT) {
                floatBuffer[i] = shortBuffer.get(i) / 32768.0f
            }
        }

        // Push to native mixer
        val framesWritten = NativeAudioEngine.pushPcm(deckId, floatBuffer, framesToProcess)

        if (framesWritten < framesToProcess) {
            // Backpressure - native buffer full
            Log.w(TAG, "Deck $deckId backpressure: wrote $framesWritten/${framesToProcess} frames")
        }
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        // We don't output to next processor - we output to native mixer directly
        // Return empty buffer to satisfy interface
        return ByteBuffer.allocateDirect(0)
    }

    override fun isEnded(): Boolean = inputEnded

    override fun flush() {
        inputEnded = false
        pendingInputBuffer = null
        pendingInputFrames = 0
    }

    override fun reset() {
        flush()
        active = false
        stopBackpressureMonitor()
    }

    private fun flushPending() {
        if (pendingInputBuffer != null && pendingInputFrames > 0) {
            queueInput(pendingInputBuffer!!)
            pendingInputBuffer = null
            pendingInputFrames = 0
        }
    }

    private fun startBackpressureMonitor() {
        monitorJob = scope.launch {
            while (active) {
                withContext(Dispatchers.IO) {
                    val fillRatio = NativeAudioEngine.getFillRatio(deckId)
                    val shouldThrottle = fillRatio > NativeAudioEngine.BACKPRESSURE_THRESHOLD

                    if (shouldThrottle != backpressureActive) {
                        backpressureActive = shouldThrottle
                        onBackpressureChange(shouldThrottle)
                    }
                }
                // Check every 10ms
                kotlinx.coroutines.delay(10)
            }
        }
    }

    private fun stopBackpressureMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        if (backpressureActive) {
            backpressureActive = false
            onBackpressureChange(false)
        }
    }
}