package com.example.kilo.playback

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * JNI Bridge to Native C++ Crossfade & Automix Engine
 *
 * Manages the native mixer lifecycle and provides Kotlin-friendly APIs
 * for PCM feeding, crossfade control, and backpressure monitoring.
 */
object NativeAudioEngine {
    private const val TAG = "KiloNativeAudio"

    // Deck identifiers (must match JNI enum)
    const val DECK_A = 0
    const val DECK_B = 1

    // Standard audio format for internal processing
    const val INTERNAL_SAMPLE_RATE = 48000
    const val INTERNAL_CHANNEL_COUNT = 2 // Stereo
    const val BYTES_PER_FRAME = 4 * INTERNAL_CHANNEL_COUNT // float32 * stereo
    const val BACKPRESSURE_THRESHOLD = 0.8f // 80% full = pause feeding

    @Volatile
    private var initialized = false

    // Native method declarations
    @Suppress("UNUSED_PARAMETER")
    external fun nativeInit(sampleRate: Int, channelCount: Int): Boolean

    @Suppress("UNUSED_PARAMETER")
    external fun nativeRelease()

    @Suppress("UNUSED_PARAMETER")
    external fun nativePushPcm(deckId: Int, buffer: FloatArray, numFrames: Int): Int

    @Suppress("UNUSED_PARAMETER")
    external fun nativeStartCrossfade(durationFrames: Long, enableAutomixEq: Boolean)

    @Suppress("UNUSED_PARAMETER")
    external fun nativeStopDeck(deckId: Int)

    @Suppress("UNUSED_PARAMETER")
    external fun nativeIsCrossfading(): Boolean

    @Suppress("UNUSED_PARAMETER")
    external fun nativeGetFillRatio(deckId: Int): Float

    @Suppress("UNUSED_PARAMETER")
    external fun nativeGetAvailableFrames(deckId: Int): Int

    @Suppress("UNUSED_PARAMETER")
    external fun nativeGetSampleRate(): Int

    /**
     * Initialize the native audio engine.
     * Must be called before any other methods.
     */
    fun initialize(context: Context): Boolean {
        if (initialized) {
            Log.d(TAG, "NativeAudioEngine already initialized")
            return true
        }

        // Load native library
        try {
            System.loadLibrary("kilomixer")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load kilomixer library", e)
            return false
        }

        val success = nativeInit(INTERNAL_SAMPLE_RATE, INTERNAL_CHANNEL_COUNT)
        initialized = success

        if (success) {
            Log.i(TAG, "NativeAudioEngine initialized: ${INTERNAL_SAMPLE_RATE}Hz, ${INTERNAL_CHANNEL_COUNT}ch")
        } else {
            Log.e(TAG, "NativeAudioEngine initialization failed")
        }

        return success
    }

    /**
     * Release native resources.
     */
    fun release() {
        if (initialized) {
            nativeRelease()
            initialized = false
            Log.i(TAG, "NativeAudioEngine released")
        }
    }

    /**
     * Push PCM frames to the specified deck.
     * Called from DeckAudioProcessor's queueInput.
     * @return Number of frames actually written (may be less due to backpressure)
     */
    fun pushPcm(deckId: Int, buffer: FloatArray, numFrames: Int): Int {
        if (!initialized) return -1
        return nativePushPcm(deckId, buffer, numFrames)
    }

    /**
     * Start a crossfade transition from current deck to the other deck.
     * @param crossfadeDurationMs Crossfade duration in milliseconds
     * @param enableAutomixEq Whether to enable Automix EQ sweep (HPF 20Hz->350Hz)
     */
    fun startCrossfade(crossfadeDurationMs: Long, enableAutomixEq: Boolean = true) {
        if (!initialized) return

        // Convert duration to frames at internal sample rate
        val durationFrames = (crossfadeDurationMs * INTERNAL_SAMPLE_RATE / 1000).toLong()
        nativeStartCrossfade(durationFrames, enableAutomixEq)
        Log.d(TAG, "Crossfade started: ${crossfadeDurationMs}ms (${durationFrames} frames), automixEQ=$enableAutomixEq")
    }

    /**
     * Stop a deck and clear its buffer.
     */
    fun stopDeck(deckId: Int) {
        if (!initialized) return
        nativeStopDeck(deckId)
    }

    /**
     * Check if a crossfade is currently in progress.
     */
    fun isCrossfading(): Boolean = initialized && nativeIsCrossfading()

    /**
     * Get fill ratio for backpressure monitoring [0.0, 1.0].
     */
    fun getFillRatio(deckId: Int): Float = if (initialized) nativeGetFillRatio(deckId) else 0f

    /**
     * Get available frames in deck buffer.
     */
    fun getAvailableFrames(deckId: Int): Int = if (initialized) nativeGetAvailableFrames(deckId) else 0

    /**
     * Get internal sample rate.
     */
    fun getSampleRate(): Int = if (initialized) nativeGetSampleRate() else INTERNAL_SAMPLE_RATE
}