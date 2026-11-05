package com.example.kilo.audio.dsp

import com.example.kilo.audioanalysis.TrackAnalysisEntity
import kotlin.math.abs

/**
 * Bounded loudness matching based on KILO's cached whole-track RMS analysis.
 * This is an RMS estimate, not an EBU R128 LUFS or embedded ReplayGain scan.
 */
class ReplayGainEngine {
    fun trackGainDb(analysis: TrackAnalysisEntity?, targetRmsDb: Double = TARGET_RMS_DB, limitDb: Float = 3f): Float =
        analysis?.loudnessDb?.let { (targetRmsDb - it).toFloat().coerceIn(-limitDb, limitDb) } ?: 0f

    /** Album matching uses the album's mean measured level, preserving relative track differences. */
    fun albumGainDb(analyses: List<TrackAnalysisEntity>, targetRmsDb: Double = TARGET_RMS_DB, limitDb: Float = 3f): Float? =
        meanGain(analyses, targetRmsDb, limitDb)

    /** Library matching is available for a pre-scanned library and remains conservatively bounded. */
    fun libraryGainDb(analyses: List<TrackAnalysisEntity>, targetRmsDb: Double = TARGET_RMS_DB, limitDb: Float = 3f): Float? =
        meanGain(analyses, targetRmsDb, limitDb)

    private fun meanGain(analyses: List<TrackAnalysisEntity>, targetRmsDb: Double, limitDb: Float): Float? {
        val levels = analyses.map { it.loudnessDb }.filter { it.isFinite() && abs(it) <= 100.0 }
        if (levels.isEmpty()) return null
        return (targetRmsDb - levels.average()).toFloat().coerceIn(-limitDb, limitDb)
    }

    // A slightly more present target improves quiet playback while gain remains bounded by
    // the output-route ceiling and the active platform limiter.
    private companion object { const val TARGET_RMS_DB = -16.5 }
}
