package com.example.kilo.playbackoptimization

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Bounded, background-only artwork cache; Android Auto callers request 256px entries. */
object ArtworkWarmupManager {
    private const val MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024
    private const val MAX_ENCODED_BYTES = 384 * 1024
    private const val MAX_DISK_BYTES = 12L * 1024L * 1024L
    private const val MAX_MEMORY_BYTES = 3 * 1024 * 1024
    private val memory = object : LruCache<String, ByteArray>(MAX_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(20),
        { task -> Thread(task, "kilo-artwork-warmup").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun warm(context: android.content.Context, artworkUri: String, maxPixels: Int) {
        val normalizedSize = maxPixels.coerceIn(128, 512)
        if (!isRemoteImage(artworkUri)) return
        val key = keyFor(artworkUri, normalizedSize)
        if (memory.get(key) != null || !inFlight.add(key)) return
        val app = context.applicationContext
        try {
            worker.execute {
            try {
                memory.get(key)?.let { return@execute }
                val file = cacheFile(app, key)
                if (file.isFile && file.length() in 1..MAX_ENCODED_BYTES.toLong()) {
                    val cached = runCatching { file.readBytes() }.getOrNull()
                    if (cached != null && cached.isNotEmpty()) {
                        memory.put(key, cached)
                        return@execute
                    }
                }
                val connection = (URL(artworkUri).openConnection() as? HttpURLConnection) ?: return@execute
                try {
                    connection.connectTimeout = 2_500
                    connection.readTimeout = 2_500
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*;q=0.5")
                    connection.setRequestProperty("User-Agent", "KILO artwork warmup")
                    if (connection.responseCode !in 200..299) return@execute
                    if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) return@execute
                    val bytes = connection.inputStream.use { readBounded(it, MAX_DOWNLOAD_BYTES) } ?: return@execute
                    val encoded = resizeAndEncode(bytes, normalizedSize) ?: return@execute
                    if (encoded.size > MAX_ENCODED_BYTES) return@execute
                    memory.put(key, encoded)
                    runCatching {
                        file.parentFile?.mkdirs()
                        val temp = File(file.parentFile, "${file.name}.tmp")
                        temp.writeBytes(encoded)
                        if (!temp.renameTo(file)) {
                            temp.copyTo(file, overwrite = true)
                            temp.delete()
                        }
                        trimDisk(file.parentFile)
                    }
                    PlaybackMetricsManager.record("artwork_warmed", details = "size=${normalizedSize} key=${key.take(12)}")
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                // Artwork is optional; a failed warmup must never affect playback or browse.
            } finally {
                inFlight.remove(key)
            }
            }
        } catch (_: RejectedExecutionException) {
            inFlight.remove(key)
        }
    }

    /** Memory-only lookup so Android Auto callbacks never wait for disk or network I/O. */
    fun cachedArtwork(artworkUri: String, maxPixels: Int = 256): ByteArray? {
        if (!isRemoteImage(artworkUri)) return null
        return memory.get(keyFor(artworkUri, maxPixels.coerceIn(128, 512)))
            ?.takeIf { it.size <= MAX_ENCODED_BYTES }
    }

    private fun resizeAndEncode(source: ByteArray, maxPixels: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxPixels * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: return null
        val scale = minOf(1f, maxPixels.toFloat() / maxOf(decoded.width, decoded.height).toFloat())
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        return try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun trimDisk(directory: File?) {
        val files = directory?.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_DISK_BYTES) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun cacheFile(context: android.content.Context, key: String): File =
        File(File(context.cacheDir, "playback_artwork_warm"), "$key.jpg")

    private fun isRemoteImage(uri: String): Boolean = runCatching {
        Uri.parse(uri).scheme?.lowercase() in setOf("http", "https")
    }.getOrDefault(false)

    private fun keyFor(uri: String, maxPixels: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$maxPixels\u001f$uri".toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
