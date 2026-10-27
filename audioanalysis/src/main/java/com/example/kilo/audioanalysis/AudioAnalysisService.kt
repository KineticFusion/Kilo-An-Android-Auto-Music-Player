package com.example.kilo.audioanalysis

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

@Entity(tableName = "track_analysis")
data class TrackAnalysisEntity(
    @PrimaryKey val trackId: String,
    val bpm: Double?,
    val musicalKey: String?,
    val energy: Double,
    val mood: String,
    val danceability: Double,
    val loudnessDb: Double,
    val instrumentalGenerated: Boolean = false,
    val analysisVersion: Int = CURRENT_ANALYSIS_VERSION,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    companion object { const val CURRENT_ANALYSIS_VERSION = 1 }
}

@Dao
interface TrackAnalysisDao {
    @Query("SELECT * FROM track_analysis WHERE trackId = :trackId LIMIT 1")
    fun get(trackId: String): TrackAnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(value: TrackAnalysisEntity)

    @Query("UPDATE track_analysis SET instrumentalGenerated = :generated WHERE trackId = :trackId")
    fun setInstrumentalGenerated(trackId: String, generated: Boolean)

    @Query("DELETE FROM track_analysis WHERE analysisVersion != :version")
    fun removeOutdated(version: Int)
}

@Entity(tableName = "recognition_history")
data class RecognitionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String = ""
)

@Dao
interface RecognitionHistoryDao {
    @Query("SELECT * FROM recognition_history ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int): List<RecognitionHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun add(value: RecognitionHistoryEntity)

    @Query("DELETE FROM recognition_history WHERE id NOT IN (SELECT id FROM recognition_history ORDER BY timestamp DESC LIMIT 50)")
    fun trim()
}

@Database(entities = [TrackAnalysisEntity::class, RecognitionHistoryEntity::class], version = 1, exportSchema = false)
abstract class AudioAnalysisDatabase : RoomDatabase() {
    abstract fun trackAnalysisDao(): TrackAnalysisDao
    abstract fun recognitionHistoryDao(): RecognitionHistoryDao
}

object AudioAnalysisDatabaseProvider {
    @Volatile private var instance: AudioAnalysisDatabase? = null

    fun get(context: Context): AudioAnalysisDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            AudioAnalysisDatabase::class.java,
            "kilo_audio_analysis.db"
        ).build().also { instance = it }
    }
}

/** Small bounded memory cache. Database access and PCM analysis are always delegated to a caller executor. */
class TrackAnalysisCache(private val capacity: Int = 128) {
    private val entries = object : LinkedHashMap<String, TrackAnalysisEntity>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackAnalysisEntity>?): Boolean =
            size > capacity.coerceAtLeast(1)
    }

    @Synchronized fun get(trackId: String): TrackAnalysisEntity? = entries[trackId]
    @Synchronized fun put(value: TrackAnalysisEntity) { entries[value.trackId] = value }
    @Synchronized fun clear() = entries.clear()
}

/** Shared analysis API. Pass only short PCM windows; callers own decoding and scheduling. */
class AudioAnalysisService(
    private val dao: TrackAnalysisDao,
    private val cache: TrackAnalysisCache = TrackAnalysisCache(),
    private val analyzer: PcmTrackAnalyzer = PcmTrackAnalyzer()
) {
    /** Memory-only lookup: safe for hot paths and never performs disk I/O. */
    fun cached(trackId: String): TrackAnalysisEntity? = cache.get(trackId)

    /** Disk access is always dispatched to the supplied background executor. */
    fun loadCachedAsync(trackId: String, executor: Executor): CompletableFuture<TrackAnalysisEntity?> =
        CompletableFuture.supplyAsync({
            if (trackId.isBlank()) return@supplyAsync null
            cache.get(trackId) ?: dao.get(trackId)?.also(cache::put)
        }, executor)

    fun analyzeAsync(
        trackId: String,
        interleavedPcm: FloatArray,
        sampleRateHz: Int,
        channelCount: Int,
        executor: Executor
    ): CompletableFuture<TrackAnalysisEntity?> = CompletableFuture.supplyAsync({
        if (trackId.isBlank()) return@supplyAsync null
        (cache.get(trackId) ?: dao.get(trackId)?.also(cache::put))
            ?.takeIf { it.analysisVersion == TrackAnalysisEntity.CURRENT_ANALYSIS_VERSION }
            ?.let { return@supplyAsync it }
        if (interleavedPcm.isEmpty() || sampleRateHz < 8_000 || channelCount !in 1..8) return@supplyAsync null
        analyzer.analyze(trackId, interleavedPcm, sampleRateHz, channelCount).also {
            dao.put(it)
            cache.put(it)
        }
    }, executor)

    fun markInstrumentalGeneratedAsync(trackId: String, generated: Boolean, executor: Executor): CompletableFuture<Unit> =
        CompletableFuture.supplyAsync({
            dao.setInstrumentalGenerated(trackId, generated)
            cache.get(trackId)?.let { cache.put(it.copy(instrumentalGenerated = generated)) }
            Unit
        }, executor)
}

/** Dependency-free PCM feature extraction, intended for bounded background jobs only. */
class PcmTrackAnalyzer {
    fun analyze(trackId: String, interleaved: FloatArray, sampleRateHz: Int, channels: Int): TrackAnalysisEntity {
        val (mono, analysisRate) = downmixAndLimit(interleaved, channels, sampleRateHz, MAX_ANALYSIS_SECONDS)
        var squareSum = 0.0
        for (sample in mono) squareSum += sample.toDouble() * sample
        val rms = sqrt((squareSum / mono.size.coerceAtLeast(1)).coerceAtLeast(1e-12))
        val loudness = (20.0 * kotlin.math.log10(rms)).coerceIn(-100.0, 0.0)
        val energy = ((loudness + 55.0) / 55.0 * 100.0).coerceIn(0.0, 100.0)
        val (bpm, beatConfidence) = estimateBpm(mono, analysisRate)
        val key = estimateKey(mono, analysisRate)
        val danceability = if (bpm == null) beatConfidence * 55.0 else {
            val tempoFit = (1.0 - abs(bpm - 120.0) / 120.0).coerceIn(0.0, 1.0)
            (beatConfidence * 65.0 + tempoFit * 35.0).coerceIn(0.0, 100.0)
        }
        val mood = when {
            energy >= 72 && bpm != null && bpm >= 112 -> "High Energy"
            energy <= 28 && (bpm == null || bpm < 82) -> "Calm"
            energy < 48 -> "Mellow"
            bpm != null && bpm >= 112 -> "Upbeat"
            else -> "Balanced"
        }
        return TrackAnalysisEntity(
            trackId = trackId,
            bpm = bpm,
            musicalKey = key,
            energy = energy,
            mood = mood,
            danceability = danceability,
            loudnessDb = loudness
        )
    }

    private fun downmixAndLimit(input: FloatArray, channels: Int, sampleRate: Int, seconds: Int): Pair<FloatArray, Int> {
        val frameCount = input.size / channels
        val maxFrames = min(frameCount, sampleRate * seconds)
        if (maxFrames <= 0) return FloatArray(0) to min(sampleRate, ANALYSIS_RATE_HZ)
        val outputRate = min(sampleRate, ANALYSIS_RATE_HZ)
        val outputFrames = ((maxFrames.toLong() * outputRate + sampleRate - 1) / sampleRate).toInt()
        val result = FloatArray(outputFrames)
        for (out in 0 until outputFrames) {
            val frame = (out.toLong() * sampleRate / outputRate).toInt().coerceAtMost(maxFrames - 1)
            var sample = 0.0
            val base = frame * channels
            for (channel in 0 until channels) sample += input[base + channel]
            result[out] = (sample / channels).toFloat().coerceIn(-1f, 1f)
        }
        return result to outputRate
    }

    private fun estimateBpm(signal: FloatArray, analysisSampleRate: Int): Pair<Double?, Double> {
        if (signal.size < ANALYSIS_RATE_HZ * 3) return null to 0.0
        val hop = 256
        val envelope = FloatArray(signal.size / hop)
        for (index in envelope.indices) {
            val start = index * hop
            var sum = 0.0
            val end = min(signal.size, start + hop)
            for (sample in start until end) sum += signal[sample].toDouble() * signal[sample]
            envelope[index] = sqrt(sum / (end - start).coerceAtLeast(1)).toFloat()
        }
        val mean = envelope.average().toFloat()
        for (i in envelope.indices) envelope[i] = (envelope[i] - mean).coerceAtLeast(0f)
        val envelopeRate = analysisSampleRate.toDouble() / hop
        val minLag = (envelopeRate * 60.0 / 200.0).toInt().coerceAtLeast(1)
        val maxLag = (envelopeRate * 60.0 / 55.0).toInt().coerceAtMost(envelope.size / 2)
        var bestLag = -1
        var bestScore = 0.0
        var baseEnergy = 0.0
        for (i in envelope.indices) baseEnergy += envelope[i].toDouble() * envelope[i]
        if (baseEnergy <= 1e-7) return null to 0.0
        for (lag in minLag..maxLag) {
            var correlation = 0.0
            for (i in 0 until envelope.size - lag) correlation += envelope[i].toDouble() * envelope[i + lag]
            val score = correlation / baseEnergy
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        if (bestLag <= 0) return null to 0.0
        val bpm = 60.0 * envelopeRate / bestLag
        return bpm.takeIf { it in 55.0..200.0 } to bestScore.coerceIn(0.0, 1.0)
    }

    private fun estimateKey(signal: FloatArray, sampleRate: Int): String? {
        if (signal.size < 4096) return null
        val downsampled = signal.take(KEY_SAMPLE_LIMIT).toFloatArray()
        val actualRate = sampleRate.toDouble()
        val chroma = DoubleArray(12)
        for (note in 0 until 12) {
            for (octave in 2..6) {
                val frequency = 440.0 * 2.0.pow((note - 9 + (octave - 4) * 12) / 12.0)
                if (frequency !in 55.0..3_500.0) continue
                chroma[note] += goertzelPower(downsampled, actualRate, frequency)
            }
        }
        val total = chroma.sum()
        if (total <= 1e-10) return null
        val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        var best = Double.NEGATIVE_INFINITY
        var key: String? = null
        for (root in 0 until 12) {
            val majorScore = profileCorrelation(chroma, major, root)
            if (majorScore > best) { best = majorScore; key = "${names[root]} Major" }
            val minorScore = profileCorrelation(chroma, minor, root)
            if (minorScore > best) { best = minorScore; key = "${names[root]} Minor" }
        }
        return key
    }

    private fun goertzelPower(samples: FloatArray, sampleRate: Double, frequency: Double): Double {
        val omega = 2.0 * Math.PI * frequency / sampleRate
        val coefficient = 2.0 * cos(omega)
        var previous = 0.0
        var previous2 = 0.0
        for (sample in samples) {
            val current = sample + coefficient * previous - previous2
            previous2 = previous
            previous = current
        }
        return max(0.0, previous2 * previous2 + previous * previous - coefficient * previous * previous2)
    }

    private fun profileCorrelation(chroma: DoubleArray, template: DoubleArray, root: Int): Double {
        val meanChroma = chroma.average()
        val meanTemplate = template.average()
        var numerator = 0.0
        var left = 0.0
        var right = 0.0
        for (index in 0 until 12) {
            val a = chroma[(index + root) % 12] - meanChroma
            val b = template[index] - meanTemplate
            numerator += a * b
            left += a * a
            right += b * b
        }
        return numerator / sqrt((left * right).coerceAtLeast(1e-12))
    }

    private companion object {
        const val ANALYSIS_RATE_HZ = 11_025
        const val MAX_ANALYSIS_SECONDS = 8
        const val KEY_SAMPLE_LIMIT = ANALYSIS_RATE_HZ * 8
    }
}
