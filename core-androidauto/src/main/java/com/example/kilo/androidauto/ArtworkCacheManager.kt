package com.example.kilo.androidauto

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor

/** Local-only, non-blocking artwork for Android Auto browse cards. */
object ArtworkCacheManager {
    private const val CACHE_DIR = "android_auto_artwork_256"
    private const val MAX_MEMORY_BYTES = 2 * 1024 * 1024

    private val artworkIds = mapOf(
        "home" to R.drawable.aa_art_home,
        "browse" to R.drawable.aa_art_browse,
        "radio" to R.drawable.aa_art_radio,
        "moods" to R.drawable.aa_art_moods,
        "genres" to R.drawable.aa_art_genres,
        "library" to R.drawable.aa_art_library,
        "downloads" to R.drawable.aa_art_downloads,
        "search" to R.drawable.aa_art_search,
        "continue" to R.drawable.aa_art_continue,
        "recent" to R.drawable.aa_art_recent,
        "playlists" to R.drawable.aa_art_playlists,
        "liked" to R.drawable.aa_art_liked,
        "artists" to R.drawable.aa_art_artists
    )
    private val startupArtworkKeys = listOf("home", "radio", "moods", "genres", "library", "downloads", "recent")

    private val memoryCache = object : LruCache<String, ByteArray>(MAX_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    private val uriCache = object : LruCache<String, Uri>(artworkIds.size) {}

    fun initialize(context: Context) {
        artworkIds.keys.forEach { artworkUri(context, it) }
    }

    /** Stable packaged URI is always returned immediately, even before cache warmup finishes. */
    fun artworkUri(context: Context, key: String): Uri {
        val normalized = normalizeKey(key)
        val resourceId = artworkIds[normalized] ?: R.drawable.aa_art_home
        uriCache.get(normalized)?.let { return it }
        val uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.resources.getResourcePackageName(resourceId))
            .appendPath(context.resources.getResourceTypeName(resourceId))
            .appendPath(context.resources.getResourceEntryName(resourceId))
            .build()
        uriCache.put(normalized, uri)
        return uri
    }

    fun keyFor(mediaId: String, title: String): String {
        val id = mediaId.lowercase(Locale.ROOT)
        val label = title.lowercase(Locale.ROOT)
        return when {
            id.contains("download") || label.contains("download") || label.contains("offline") -> "downloads"
            id.contains("browse") || label == "browse" -> "browse"
            id.contains("radio") || label.contains("radio") -> "radio"
            id.contains("mood") || label.contains("mood") || label.contains("journey") -> "moods"
            id.contains("genre") || label.contains("genre") || label.contains("hits") -> "genres"
            id.contains("liked") || label.contains("liked") || label.contains("favorite") -> "liked"
            id.contains("artist") || label.contains("artist") -> "artists"
            id.contains("playlist") || id.startsWith("savedpl:") || id.startsWith("pl:") || label.contains("playlist") -> "playlists"
            id.contains("search") || label == "search" -> "search"
            id.contains("recent") || label.contains("recent") || label.contains("history") -> "recent"
            id.contains("continue") || label.contains("continue listening") -> "continue"
            id == "root" || id.contains("home") || label == "kilo" || label == "home" -> "home"
            else -> "library"
        }
    }

    /** Render bundled art off the browse thread and persist a small disk copy for the next start. */
    fun preload(context: Context, executor: Executor) {
        initialize(context)
        startupArtworkKeys.forEach { key ->
            executor.execute { loadOrRender(context.applicationContext, key) }
        }
    }

    fun cachedArtworkBytes(key: String): ByteArray? = memoryCache.get(normalizeKey(key))

    private fun loadOrRender(context: Context, key: String): ByteArray? {
        memoryCache.get(key)?.let { return it }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val file = cacheFile(context, key)
        val disk = runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
        val bytes = disk?.takeIf { it.isNotEmpty() && it.size <= 256 * 1024 } ?: render(context, key)
        if (bytes != null) {
            memoryCache.put(key, bytes)
            if (disk == null) runCatching {
                file.parentFile?.mkdirs()
                val temporary = File(file.parentFile, "${file.name}.tmp")
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(file)) {
                    temporary.copyTo(file, overwrite = true)
                    temporary.delete()
                }
            }
        }
        AndroidAutoMetrics.recordArtworkLoad(
            key,
            android.os.SystemClock.elapsedRealtime() - startedAt,
            succeeded = bytes != null
        )
        return bytes
    }

    private fun render(context: Context, key: String): ByteArray? = runCatching {
        val resourceId = artworkIds[key] ?: R.drawable.aa_art_home
        val drawable = context.getDrawable(resourceId) ?: return null
        // A 256x160 preview is sufficient for Android Auto and uses 1/4 of the old pixel memory.
        val bitmap = Bitmap.createBitmap(256, 160, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).also { canvas ->
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }.getOrNull()

    private fun cacheFile(context: Context, key: String): File =
        File(File(context.cacheDir, CACHE_DIR), "$key.png")

    private fun normalizeKey(key: String): String = key.lowercase(Locale.ROOT)
        .takeIf(artworkIds::containsKey) ?: "home"
}
