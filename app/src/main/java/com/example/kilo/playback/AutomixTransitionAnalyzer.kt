package com.example.kilo.playback

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Automix Transition Analyzer - Smart Outro/Intro Detection
 *
 * Analyzes decoded PCM audio buffers to detect:
 * - Outro: When outgoing track energy drops below threshold (silence/fade-out)
 * - Intro: Leading silence on incoming track (trim to beat drop)
 *
 * Uses RMS energy analysis on stereo float32 PCM at 48kHz.
 */
object AutomixTransitionAnalyzer {
    private const val TAG = "AutomixAnalyzer"

    // Analysis parameters
    private const val ANALYSIS_WINDOW_MS = 100 // 100ms analysis windows
    private const val SAMPLE_RATE = 48000
    private const val SAMPLES_PER_WINDOW = SAMPLE_RATE * ANALYSIS_WINDOW_MS / 1000 // 4800 samples
    private const val CHANNELS = 2

    // Energy thresholds (dBFS)
    const val OUTRO_THRESHOLD_DB = -24.0f // Outgoing track outro detection
    private const val SILENCE_THRESHOLD_DB = -45.0f // Leading silence detection
    private const val MIN_AUDIBLE_DB = -60.0f // Noise floor

    // Crossfade timing (Long for millisecond precision)
    private const val DEFAULT_CROSSFADE_MS = 8000L // 8 seconds default
    private const val MIN_CROSSFADE_MS = 4000L
    private const val MAX_CROSSFADE_MS = 12000L

    data class AnalysisResult(
        val outroStartMs: Long, // When outgoing track outro begins (energy drops)
        val introEndMs: Long,   // When incoming track intro ends (first beat)
        val recommendedCrossfadeMs: Long,
        val hasValidOutro: Boolean,
        val hasValidIntro: Boolean
    )

    /**
     * Analyze a PCM buffer to detect outro/intro points.
     *
     * @param pcmBuffer FloatArray of interleaved stereo float32 samples
     * @param totalFrames Total frames in buffer
     * @param isOutgoing True if analyzing outgoing track (outro), false for incoming (intro)
     * @return AnalysisResult with detected transition points
     */
    suspend fun analyze(
        pcmBuffer: FloatArray,
        totalFrames: Int,
        isOutgoing: Boolean
    ): AnalysisResult = withContext(Dispatchers.IO) {
        if (totalFrames == 0 || pcmBuffer.size < totalFrames * CHANNELS) {
            return@withContext AnalysisResult(
                outroStartMs = if (isOutgoing) (totalFrames * 1000L / SAMPLE_RATE) - DEFAULT_CROSSFADE_MS else 0,
                introEndMs = if (!isOutgoing) 0 else 0,
                recommendedCrossfadeMs = DEFAULT_CROSSFADE_MS,
                hasValidOutro = false,
                hasValidIntro = false
            )
        }

        if (isOutgoing) {
            analyzeOutro(pcmBuffer, totalFrames)
        } else {
            analyzeIntro(pcmBuffer, totalFrames)
        }
    }

    /**
     * Analyze outgoing track for outro (fade-out/silence at end).
     * Scans from end backwards to find where energy drops below threshold.
     */
    private fun analyzeOutro(pcmBuffer: FloatArray, totalFrames: Int): AnalysisResult {
        val windowFrames = SAMPLES_PER_WINDOW
        val numWindows = totalFrames / windowFrames

        if (numWindows < 3) {
            // Too short for meaningful analysis
            return AnalysisResult(
                outroStartMs = maxOf(0, (totalFrames * 1000L / SAMPLE_RATE) - DEFAULT_CROSSFADE_MS),
                introEndMs = 0,
                recommendedCrossfadeMs = DEFAULT_CROSSFADE_MS,
                hasValidOutro = false,
                hasValidIntro = false
            )
        }

        // Analyze windows from end towards start
        var outroWindow = -1
        var prevEnergyDb = Float.MAX_VALUE

        for (w in numWindows - 1 downTo 0) {
            val startFrame = w * windowFrames
            val energyDb = computeRmsDb(pcmBuffer, startFrame, windowFrames)

            // Detect significant energy drop (outro start)
            if (prevEnergyDb != Float.MAX_VALUE) {
                val energyDrop = prevEnergyDb - energyDb
                if (energyDrop > 6.0f && energyDb < OUTRO_THRESHOLD_DB) {
                    // Found outro start - significant drop and now below threshold
                    outroWindow = w
                    break
                }
            }

            // Also check if we're in sustained low energy (fade to silence)
            if (energyDb < OUTRO_THRESHOLD_DB && prevEnergyDb < OUTRO_THRESHOLD_DB) {
                outroWindow = w
            }

            prevEnergyDb = energyDb
        }

        val outroStartMs = if (outroWindow >= 0) {
            (outroWindow * windowFrames * 1000L / SAMPLE_RATE).toLong()
        } else {
            maxOf(0, (totalFrames * 1000L / SAMPLE_RATE) - DEFAULT_CROSSFADE_MS)
        }

        val trackDurationMs = (totalFrames * 1000L / SAMPLE_RATE).toLong()
        val recommendedCrossfadeMs = when {
            trackDurationMs - outroStartMs > MAX_CROSSFADE_MS -> MAX_CROSSFADE_MS
            trackDurationMs - outroStartMs < MIN_CROSSFADE_MS -> MIN_CROSSFADE_MS
            else -> trackDurationMs - outroStartMs
        }.coerceIn(MIN_CROSSFADE_MS, MAX_CROSSFADE_MS)

        Log.d(TAG, "Outro analysis: outroStart=${outroStartMs}ms, duration=${trackDurationMs}ms, crossfade=${recommendedCrossfadeMs}ms")

        return AnalysisResult(
            outroStartMs = outroStartMs,
            introEndMs = 0,
            recommendedCrossfadeMs = recommendedCrossfadeMs,
            hasValidOutro = outroWindow >= 0,
            hasValidIntro = false
        )
    }

    /**
     * Analyze incoming track for intro (leading silence before first beat).
     * Scans from start forwards to find where energy rises above silence threshold.
     */
    private fun analyzeIntro(pcmBuffer: FloatArray, totalFrames: Int): AnalysisResult {
        val windowFrames = SAMPLES_PER_WINDOW
        val numWindows = totalFrames / windowFrames

        if (numWindows < 3) {
            return AnalysisResult(
                outroStartMs = 0,
                introEndMs = 0,
                recommendedCrossfadeMs = DEFAULT_CROSSFADE_MS,
                hasValidOutro = false,
                hasValidIntro = false
            )
        }

        // Analyze windows from start forwards
        var introWindow = -1
        var foundAudio = false

        for (w in 0 until numWindows) {
            val startFrame = w * windowFrames
            val energyDb = computeRmsDb(pcmBuffer, startFrame, windowFrames)

            if (!foundAudio && energyDb > SILENCE_THRESHOLD_DB) {
                // Found first audible content
                foundAudio = true
                introWindow = w
                break
            }
        }

        val introEndMs = if (introWindow >= 0) {
            (introWindow * windowFrames * 1000L / SAMPLE_RATE).toLong()
        } else {
            0L
        }

        Log.d(TAG, "Intro analysis: introEnd=${introEndMs}ms, hasAudio=$foundAudio")

        return AnalysisResult(
            outroStartMs = 0,
            introEndMs = introEndMs,
            recommendedCrossfadeMs = DEFAULT_CROSSFADE_MS,
            hasValidOutro = false,
            hasValidIntro = foundAudio
        )
    }

    /**
     * Compute RMS energy in dBFS for a window of stereo float32 samples.
     */
    private fun computeRmsDb(buffer: FloatArray, startFrame: Int, numFrames: Int): Float {
        var sumSq = 0.0
        val endSample = (startFrame + numFrames) * CHANNELS
        val startSample = startFrame * CHANNELS

        for (i in startSample until endSample step CHANNELS) {
            val left = buffer[i]
            val right = buffer[i + 1]
            sumSq += (left * left + right * right) * 0.5 // Average of channels
        }

        val rms = Math.sqrt(sumSq / numFrames)
        return if (rms > 0.0) {
            (20.0 * Math.log10(rms)).toFloat()
        } else {
            MIN_AUDIBLE_DB
        }
    }

    /**
     * Quick energy check for real-time monitoring during playback.
     * Returns current RMS dBFS of the last N frames.
     */
    fun getCurrentEnergyDb(pcmBuffer: FloatArray, framesToAnalyze: Int): Float {
        val totalFrames = pcmBuffer.size / CHANNELS
        val startFrame = maxOf(0, totalFrames - framesToAnalyze)
        return computeRmsDb(pcmBuffer, startFrame, totalFrames - startFrame)
    }

    /**
     * Check if outgoing track has reached outro threshold (for triggering crossfade).
     */
    fun isOutroTriggered(pcmBuffer: FloatArray, lookbackFrames: Int = SAMPLES_PER_WINDOW): Boolean {
        val energyDb = getCurrentEnergyDb(pcmBuffer, lookbackFrames)
        return energyDb < OUTRO_THRESHOLD_DB
    }
}