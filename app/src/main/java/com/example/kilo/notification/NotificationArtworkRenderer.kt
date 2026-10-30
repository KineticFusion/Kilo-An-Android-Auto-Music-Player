package com.example.kilo.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.graphics.Color
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Bounded 256px artwork loading with a small disk cache; callers should run it off-main. */
object NotificationArtworkRenderer {
    private const val MAX_ARTWORK_BYTES = 6 * 1024 * 1024
    private const val TARGET_EDGE_PX = 256
    private val fallbackArtwork by lazy(LazyThreadSafetyMode.PUBLICATION) { createPlaceholder() }

    fun load(context: Context, artworkUri: Uri): Bitmap? = runCatching {
        val cacheDirectory = File(context.cacheDir, "kilo_notification_artwork").apply { mkdirs() }
        val cacheFile = File(cacheDirectory, "${cacheKey(artworkUri.toString())}.img")
        if (cacheFile.isFile && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL_MS) {
            decode(cacheFile.readBytes())?.let { return it }
        }
        val bytes = when (artworkUri.scheme?.lowercase()) {
            "content", "android.resource" -> context.contentResolver.openInputStream(artworkUri)?.use(::readBounded)
            else -> {
                val connection = URL(artworkUri.toString()).openConnection() as HttpURLConnection
                connection.connectTimeout = 4_000
                connection.readTimeout = 5_000
                connection.instanceFollowRedirects = true
                try {
                    if (connection.contentLengthLong > MAX_ARTWORK_BYTES) return null
                    connection.inputStream.use(::readBounded)
                } finally {
                    connection.disconnect()
                }
            }
        } ?: return null
        decode(bytes)?.also { bitmap ->
            // Cache only the bounded, already-downsampled artwork bytes when possible.
            runCatching {
                val compressed = ByteArrayOutputStream().also { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 84, stream)
                }.toByteArray()
                cacheFile.writeBytes(compressed)
            }
        }
    }.getOrNull()

    fun placeholder(): Bitmap = fallbackArtwork

    private fun createPlaceholder(): Bitmap = Bitmap.createBitmap(TARGET_EDGE_PX, TARGET_EDGE_PX, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(20, 17, 30))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(177, 119, 255)
            style = Paint.Style.FILL
        }
        val widths = intArrayOf(12, 18, 14, 18, 12)
        val heights = intArrayOf(42, 76, 110, 76, 42)
        val gap = 12
        val totalWidth = widths.sum() + gap * (widths.size - 1)
        var x = (TARGET_EDGE_PX - totalWidth) / 2f
        widths.indices.forEach { index ->
            val top = (TARGET_EDGE_PX - heights[index]) / 2f
            canvas.drawRoundRect(x, top, x + widths[index], top + heights[index], 8f, 8f, paint)
            x += widths[index] + gap
        }
    }

    private fun readBounded(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ARTWORK_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > TARGET_EDGE_PX * 2 || bounds.outHeight / sample > TARGET_EDGE_PX * 2) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1); inPreferredConfig = Bitmap.Config.RGB_565 }
        ) ?: return null
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= TARGET_EDGE_PX) return bitmap
        val scale = TARGET_EDGE_PX.toFloat() / edge.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        ).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun cacheKey(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(16)
        .joinToString("") { "%02x".format(it) }

    private const val CACHE_TTL_MS = 14L * 24L * 60L * 60L * 1000L
}
