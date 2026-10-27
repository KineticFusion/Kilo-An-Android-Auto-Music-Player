package com.example.kilo.audioanalysis

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/** Optional PCM tap. With analysis disabled it forwards the original buffer unchanged. */
@UnstableApi
class AudioAnalysisTapProcessor(
    private val executor: Executor,
    private val onWindowCaptured: (String, FloatArray, Int, Int) -> Unit
) : AudioProcessor {
    private val enabled = AtomicBoolean(false)
    private var mediaId: String = ""
    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var output = AudioProcessor.EMPTY_BUFFER
    private var ended = false
    private var stride = 1
    private var framesCaptured = 0
    private var captureRate = 0
    private var capture = FloatArray(MAX_RATE_HZ * MAX_SECONDS)
    private var submitted = false

    fun setEnabled(value: Boolean) {
        enabled.set(value)
        if (!value) clearCapture()
    }

    fun setMediaId(value: String) {
        if (mediaId != value) {
            mediaId = value
            clearCapture()
        }
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputFormat = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT && inputAudioFormat.channelCount in 1..8) {
            inputAudioFormat
        } else AudioProcessor.AudioFormat.NOT_SET
        captureRate = min(inputAudioFormat.sampleRate, MAX_RATE_HZ).coerceAtLeast(1)
        stride = max(1, inputAudioFormat.sampleRate / captureRate)
        return inputFormat
    }

    override fun isActive(): Boolean = inputFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (!enabled.get() || submitted || mediaId.isBlank()) {
            output = inputBuffer.slice()
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val forwarded = inputBuffer.slice()
        try {
            val source = inputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
            val channels = inputFormat.channelCount
            val bytesPerFrame = channels * 2
            var frameIndex = 0
            while (source.remaining() >= bytesPerFrame && framesCaptured < capture.size) {
                var sum = 0.0
                repeat(channels) { sum += source.short.toDouble() / Short.MAX_VALUE }
                if (frameIndex % stride == 0) capture[framesCaptured++] = (sum / channels).toFloat().coerceIn(-1f, 1f)
                frameIndex++
            }
        } catch (_: Throwable) {
            enabled.set(false)
            clearCapture()
        }
        output = forwarded
        inputBuffer.position(inputBuffer.limit())
        if (framesCaptured >= capture.size && !submitted) {
            submitted = true
            val id = mediaId
            val window = capture.copyOf(framesCaptured)
            val rate = (inputFormat.sampleRate / stride).coerceAtLeast(1)
            runCatching { executor.execute { runCatching { onWindowCaptured(id, window, rate, 1) } } }
        }
    }

    override fun queueEndOfStream() { ended = true }
    override fun getOutput(): ByteBuffer = output.also { output = AudioProcessor.EMPTY_BUFFER }
    override fun isEnded(): Boolean = ended && !output.hasRemaining()
    override fun flush() { output = AudioProcessor.EMPTY_BUFFER; ended = false; clearCapture() }
    override fun reset() {
        flush()
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
    }

    private fun clearCapture() { framesCaptured = 0; submitted = false }

    private companion object {
        const val MAX_RATE_HZ = 11_025
        const val MAX_SECONDS = 8
    }
}
