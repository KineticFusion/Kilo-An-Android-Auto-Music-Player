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
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.sin

@Entity(tableName = "track_analysis")
data class TrackAnalysisEntity(
    @PrimaryKey val trackId: String,
    val bpm: Double?,
    val musicalKey: String?,
    val energy: Double,
    val mood: String,
    val danceability: Double,
    val loudnessDb: Double,
    val loudnessDbAlbum: Double? = null, // Album-normalized loudness
    val instrumentalGenerated: Boolean = false,
    val analysisVersion: Int = CURRENT_ANALYSIS_VERSION,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    companion object { const val CURRENT_ANALYSIS_VERSION = 2 } // Bumped for album loudness
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

@Database(entities = [TrackAnalysisEntity::class, RecognitionHistoryEntity::class], version = 2, exportSchema = false)
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
        ).fallbackToDestructiveMigration().build().also { instance = it }
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
    // Analyze up to 30 seconds for more accurate loudness, but cap at reasonable duration
    companion object {
        private const val MAX_ANALYSIS_SECONDS = 30
        private const val ANALYSIS_RATE_HZ = 22050
    }

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
            loudnessDb = loudness,
            loudnessDbAlbum = null // Will be computed at album level if needed
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
        val window = 2048
        val frames = (signal.size - window) / hop
        if (frames <= 0) return null to 0.0
        val flux = DoubleArray(frames)
        var prevMag = 0.0
        for (i in 0 until frames) {
            val start = i * hop
            var mag = 0.0
            for (j in 0 until window) {
                val s = signal[start + j].toDouble()
                mag += s * s
            }
            mag = sqrt(mag / window)
            flux[i] = max(0.0, mag - prevMag)
            prevMag = mag
        }
        val acf = autocorrelate(flux)
        val minLag = analysisSampleRate / hop / 200  // 200 BPM max
        val maxLag = analysisSampleRate / hop / 50   // 50 BPM min
        var bestLag = -1
        var bestVal = 0.0
        for (lag in minLag..maxLag.coerceAtMost(acf.size - 1)) {
            if (acf[lag] > bestVal) {
                bestVal = acf[lag]
                bestLag = lag
            }
        }
        if (bestLag <= 0 || bestVal < 0.2) return null to 0.0
        val bpm = (60.0 * analysisSampleRate) / (hop * bestLag)
        val confidence = bestVal.coerceIn(0.0, 1.0)
        return bpm to confidence
    }

    private fun autocorrelate(signal: DoubleArray): DoubleArray {
        val n = signal.size
        val result = DoubleArray(n)
        for (lag in 0 until n) {
            var sum = 0.0
            var count = 0
            for (i in 0 until n - lag) {
                sum += signal[i] * signal[i + lag]
                count++
            }
            result[lag] = if (count > 0) sum / count else 0.0
        }
        return result
    }

    private fun estimateKey(signal: FloatArray, analysisSampleRate: Int): String? {
        if (signal.size < ANALYSIS_RATE_HZ * 5) return null
        val hop = 1024
        val window = 4096
        val frames = (signal.size - window) / hop
        if (frames <= 0) return null
        val chroma = Array(12) { DoubleArray(frames) { 0.0 } }
        val fftSize = 4096
        val twiddleRe = DoubleArray(fftSize)
        val twiddleIm = DoubleArray(fftSize)
        for (k in 0 until fftSize) {
            val angle = -2.0 * kotlin.math.PI * k / fftSize
            twiddleRe[k] = cos(angle)
            twiddleIm[k] = sin(angle)
        }
        val windowFn = DoubleArray(window)
        for (i in 0 until window) {
            windowFn[i] = 0.5 - 0.5 * cos(2.0 * kotlin.math.PI * i / (window - 1))
        }
        for (i in 0 until frames) {
            val start = i * hop
            val re = DoubleArray(fftSize) { 0.0 }
            val im = DoubleArray(fftSize) { 0.0 }
            for (j in 0 until window) {
                re[j] = signal[start + j].toDouble() * windowFn[j]
            }
            fft(re, im, fftSize, twiddleRe, twiddleIm)
            val binHz = analysisSampleRate.toDouble() / fftSize
            for (bin in 1 until fftSize / 2) {
                val freq = bin * binHz
                if (freq < 60.0 || freq > 2000.0) continue
                val mag = sqrt(re[bin] * re[bin] + im[bin] * im[bin])
                if (mag < 1e-6) continue
                val midi = 69.0 + 12.0 * kotlin.math.log2(freq / 440.0)
                val pitchClass = ((midi.roundToInt() % 12) + 12) % 12
                chroma[pitchClass][i] += mag
            }
        }
        val profileMajor = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val profileMinor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
        val meanChroma = DoubleArray(12) { chroma[it].average() }
        val sum = meanChroma.sum()
        if (sum < 1e-6) return null
        val norm = meanChroma.map { it / sum }
        var bestKey = ""
        var bestCorr = -1.0
        val keyNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        for (root in 0 until 12) {
            var corrMaj = 0.0
            var corrMin = 0.0
            for (i in 0 until 12) {
                corrMaj += norm[i] * profileMajor[(i - root + 12) % 12]
                corrMin += norm[i] * profileMinor[(i - root + 12) % 12]
            }
            if (corrMaj > bestCorr) { bestCorr = corrMaj; bestKey = "${keyNames[root]} Major" }
            if (corrMin > bestCorr) { bestCorr = corrMin; bestKey = "${keyNames[root]} Minor" }
        }
        return if (bestCorr > 0.1) bestKey else null
    }

    private fun fft(re: DoubleArray, im: DoubleArray, n: Int, twiddleRe: DoubleArray, twiddleIm: DoubleArray) {
        var j = 0
        for (i in 1 until n - 1) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tmp = re[i]; re[i] = re[j]; re[j] = tmp
                val tmp2 = im[i]; im[i] = im[j]; im[j] = tmp2
            }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val even = i + k
                    val odd = i + k + len / 2
                    val tr = re[odd] * twiddleRe[k * step] - im[odd] * twiddleIm[k * step]
                    val ti = re[odd] * twiddleIm[k * step] + im[odd] * twiddleRe[k * step]
                    re[odd] = re[even] - tr
                    im[odd] = im[even] - ti
                    re[even] = re[even] + tr
                    im[even] = im[even] + ti
                }
            }
            len = len shl 1
        }
    }
}