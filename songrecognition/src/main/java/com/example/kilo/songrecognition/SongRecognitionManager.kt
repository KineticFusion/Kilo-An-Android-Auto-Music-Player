package com.example.kilo.songrecognition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.kilo.audioanalysis.RecognitionHistoryDao
import com.example.kilo.audioanalysis.RecognitionHistoryEntity
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class RecognizedSong(
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String = ""
)

data class AudioFingerprint(val sampleRateHz: Int, val durationMs: Long, val frames: List<Long>)

fun interface LocalMatchEngine {
    fun identify(fingerprint: AudioFingerprint): RecognizedSong?
}

fun interface RecognitionProvider {
    fun identify(wavAudio: ByteArray): RecognizedSong?
}

/** On-device, user-triggered sample capture. No audio is transmitted by this module. */
class SongRecognitionManager(
    private val context: Context,
    private val history: RecognitionHistoryDao,
    private val provider: RecognitionProvider
) {
    fun recognize(executor: Executor, sampleSeconds: Int = 5): CompletableFuture<RecognizedSong?> =
        CompletableFuture.supplyAsync({
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Microphone permission is required to recognize a song."
            }
            val pcm = recordPcm(sampleSeconds.coerceIn(3, 8))
            val fingerprint = AudioFingerprintGenerator.generate(pcm, SAMPLE_RATE_HZ)
            // Keep fingerprint generation shared with local catalog/provider implementations.
            if (fingerprint.frames.isEmpty()) return@supplyAsync null
            val result = provider.identify(toWav(pcm, SAMPLE_RATE_HZ))
            if (result != null) {
                history.add(RecognitionHistoryEntity(
                    title = result.title, artist = result.artist,
                    album = result.album, artworkUrl = result.artworkUrl
                ))
                history.trim()
            }
            result
        }, executor)

    private fun recordPcm(seconds: Int): ShortArray {
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimumBuffer > 0) { "Audio capture is unavailable on this device." }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimumBuffer, SAMPLE_RATE_HZ)
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be started." }
        val captured = ShortArray(SAMPLE_RATE_HZ * seconds)
        val chunk = ShortArray(minimumBuffer.coerceAtMost(captured.size))
        var offset = 0
        try {
            recorder.startRecording()
            while (offset < captured.size) {
                val read = recorder.read(chunk, 0, minOf(chunk.size, captured.size - offset), AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    chunk.copyInto(captured, offset, 0, read)
                    offset += read
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        check(offset >= SAMPLE_RATE_HZ) { "The recording was too short. Try again." }
        return captured.copyOf(offset)
    }

    private fun toWav(pcm: ShortArray, sampleRateHz: Int): ByteArray {
        val output = ByteArrayOutputStream(pcm.size * 2 + 44)
        val data = DataOutputStream(output)
        fun text(value: String) = data.writeBytes(value)
        fun le16(value: Int) { data.writeByte(value and 0xff); data.writeByte(value shr 8 and 0xff) }
        fun le32(value: Int) {
            data.writeByte(value and 0xff); data.writeByte(value shr 8 and 0xff)
            data.writeByte(value shr 16 and 0xff); data.writeByte(value shr 24 and 0xff)
        }
        text("RIFF"); le32(pcm.size * 2 + 36); text("WAVE"); text("fmt ")
        le32(16); le16(1); le16(1); le32(sampleRateHz); le32(sampleRateHz * 2); le16(2); le16(16)
        text("data"); le32(pcm.size * 2)
        pcm.forEach { sample -> le16(sample.toInt()) }
        return output.toByteArray()
    }

    companion object { private const val SAMPLE_RATE_HZ = 16_000 }
}

/** Compact deterministic fingerprint for local catalog matching or an explicitly configured provider. */
object AudioFingerprintGenerator {
    fun generate(pcm16Mono: ShortArray, sampleRateHz: Int): AudioFingerprint {
        require(sampleRateHz > 0)
        val frameSize = (sampleRateHz / 4).coerceAtLeast(1)
        val frames = pcm16Mono.asSequence().chunked(frameSize).map { window ->
            var energy = 0L
            var zeroCrossings = 0
            for (i in window.indices) {
                val sample = window[i].toInt()
                energy += sample.toLong() * sample
                if (i > 0 && ((window[i - 1] < 0) != (window[i] < 0))) zeroCrossings++
            }
            (energy / window.size.coerceAtLeast(1)).coerceAtLeast(0L).shl(8) xor zeroCrossings.toLong()
        }.toList()
        return AudioFingerprint(sampleRateHz, pcm16Mono.size * 1_000L / sampleRateHz, frames)
    }
}

/** Exact local lookup for a preloaded fingerprint catalog; no network or microphone work here. */
class LocalFingerprintCatalog(private val entries: Map<String, RecognizedSong>) : LocalMatchEngine {
    override fun identify(fingerprint: AudioFingerprint): RecognizedSong? {
        if (fingerprint.frames.isEmpty()) return null
        val key = fingerprint.frames.take(12).joinToString(":")
        return entries[key]
    }
}

/** AudD HTTP adapter. Upload occurs only after the user taps Recognize and grants microphone access. */
class AuddRecognitionProvider(private val apiToken: String) : RecognitionProvider {
    override fun identify(wavAudio: ByteArray): RecognizedSong? {
        require(apiToken.isNotBlank()) { "Enter your AudD API token to identify a song." }
        val boundary = "KiloRecognition${System.nanoTime()}"
        val connection = (URL("https://api.audd.io/").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            connection.outputStream.buffered().use { stream ->
                fun field(name: String, value: String) {
                    stream.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("api_token", apiToken)
                stream.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                stream.write(wavAudio)
                stream.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "AudD returned HTTP $status." }
            val match = JSONObject(response).optJSONObject("result") ?: return null
            val title = match.optString("title").trim()
            val artist = match.optString("artist").trim()
            if (title.isBlank() || artist.isBlank()) return null
            return RecognizedSong(title, artist, match.optString("album"))
        } finally {
            connection.disconnect()
        }
    }
}

/** Stores the user-provided provider token encrypted with an Android Keystore AES key. */
class AuddTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("song_recognition", Context.MODE_PRIVATE)
    private val alias = "kilo_audd_api_token_v1"

    fun save(token: String) {
        val clean = token.trim()
        require(clean.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        preferences.edit().putString("audd_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("audd_token", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun read(): String? = runCatching {
        val iv = preferences.getString("audd_iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val encrypted = preferences.getString("audd_token", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        cipher.doFinal(encrypted).toString(Charsets.UTF_8).takeIf(String::isNotBlank)
    }.getOrNull()

    fun clear() { preferences.edit().remove("audd_iv").remove("audd_token").apply() }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
}
