package com.example.kilo.audio.dsp

import com.example.kilo.audioanalysis.TrackAnalysisEntity
import kotlin.math.abs

/**
 * Bounded loudness matching based on KILO's cached whole-track RMS analysis.
 * This is an RMS estimate, not an EBU R128 LUFS or embedded ReplayGain scan.
 */
class ReplayGainEngine {
    fun trackGainDb(analysis: TrackAnalysisEntity?, targetRmsDb: Double = TARGET_RMS_DB, limitDb: Float = 3f): Float =
        analysis?.let { a ->
            // Prefer album-normalized loudness if available, otherwise use track loudness
            val loudness = a.loudnessDbAlbum?.takeIf { it.isFinite() } ?: a.loudnessDb
            (targetRmsDb - loudness).toFloat().coerceIn(-limitDb, limitDb)
        } ?: 0f

    /** Album matching uses the album's mean measured level, preserving relative track differences. */
    fun albumGainDb(analyses: List<TrackAnalysisEntity>, targetRmsDb: Double = TARGET_RMS_DB, limitDb: Float = 3f): Float? =
        meanGain(analyses, targetRmsDb, limitDb)

    /** Library matching is available for a pre-scanned library and remains conservatively bounded. */
    fun libraryGainDb(analyses: List<TrackAnalysisEntity>, targetRmsDb: Double = TARGET_RMS_DB, limitDb: Float = 3f): Float? =
        meanGain(analyses, targetRmsDb, limitDb)

    private fun meanGain(analyses: List<TrackAnalysisEntity>, targetRmsDb: Double, limitDb: Float): Float? {
        val levels = analyses.map { it.loudnessDbAlbum?.takeIf { it.isFinite() } ?: it.loudnessDb }
            .filter { it.isFinite() && abs(it) <= 100.0 }
        if (levels.isEmpty()) return null
        return (targetRmsDb - levels.average()).toFloat().coerceIn(-limitDb, limitDb)
    }

    // Slightly lower target (-18dB) for more headroom and natural dynamics
    // This prevents the limiter from engaging too aggressively on well-mastered tracks
    private companion object { const val TARGET_RMS_DB = -18.0 }
}