package com.example.kilo.androidauto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaLibraryService
import java.io.ByteArrayOutputStream

/** Builds Android Auto browse cards and style hints without sharing phone UI components. */
object AndroidAutoUiModule {
    const val STYLE_LIST = 1
    const val STYLE_GRID = 2
    const val STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
    const val BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    private const val MAX_MOOD_ARTWORK_BYTES = 1024 * 1024
    private val moodArtworkCache = object : LruCache<String, ByteArray>(MAX_MOOD_ARTWORK_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }
    private val gridParentIds = setOf(
        "root", "tab_home", "tab_search", "tab_radio", "tab_library", "tab_browse", "journeys:home",
        "tab_moods", "tab_genres", "tab_profile_artists", "tab_albums", "tab_playlists"
    )

    fun folder(
        id: String,
        title: String,
        subtitle: String? = null,
        artworkUri: Uri? = null,
        artworkData: ByteArray? = null,
        childStyle: Int = STYLE_LIST
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MIXED)
            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
            .setExtras(styleExtras(isBrowsable = true, style = childStyle))
        subtitle?.let(metadata::setSubtitle)
        artworkUri?.let(metadata::setArtworkUri)
        artworkData?.let { metadata.setArtworkData(it, null) }
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata.build()).build()
    }

    @Synchronized
    fun moodArtworkData(mood: String): ByteArray {
        val key = mood.lowercase()
        moodArtworkCache.get(key)?.let { return it }
        val (start, end) = moodColors(mood)
        val bitmap = Bitmap.createBitmap(256, 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 256f, 160f, start, end, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(0f, 0f, 256f, 160f), 28f, 28f, background)
        val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55070B16 }
        canvas.drawRoundRect(RectF(0f, 0f, 256f, 160f), 28f, 28f, shade)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
        canvas.drawCircle(213f, 34f, 56f, glow)
        val bars = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF8FAFF.toInt()
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
        }
        val heights = intArrayOf(28, 56, 82, 45, 68, 35, 58)
        heights.forEachIndexed { index, height ->
            val x = 78f + index * 18f
            val scaledHeight = height * 0.8f
            canvas.drawLine(x, 114f - scaledHeight / 2f, x, 114f + scaledHeight / 2f, bars)
        }
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)
        bitmap.recycle()
        return output.toByteArray().also { moodArtworkCache.put(key, it) }
    }

    private fun moodColors(mood: String): Pair<Int, Int> = when (mood.lowercase()) {
        "night drive" -> 0xFF11145B.toInt() to 0xFF8029B8.toInt()
        "workout" -> 0xFFB52D4B.toInt() to 0xFFEF6945.toInt()
        "commute" -> 0xFF0C7180.toInt() to 0xFF1265A2.toInt()
        "road trip" -> 0xFF965021.toInt() to 0xFFE4A53D.toInt()
        "focus" -> 0xFF1E3B75.toInt() to 0xFF168C9A.toInt()
        "relax" -> 0xFF583891.toInt() to 0xFFAB5DA9.toInt()
        "gaming" -> 0xFF322F9A.toInt() to 0xFF8D3EC7.toInt()
        "party" -> 0xFF8D275F.toInt() to 0xFFE75B83.toInt()
        "rain drive" -> 0xFF173C65.toInt() to 0xFF347EAA.toInt()
        "romantic" -> 0xFF8E315F.toInt() to 0xFFE07889.toInt()
        "sleep" -> 0xFF101B45.toInt() to 0xFF465386.toInt()
        "happy" -> 0xFFB45B1F.toInt() to 0xFFF0BD45.toInt()
        "sad" -> 0xFF283B68.toInt() to 0xFF637C9E.toInt()
        "coffee vibes" -> 0xFF64402E.toInt() to 0xFFB07A43.toInt()
        else -> 0xFF383174.toInt() to 0xFF367CC0.toInt()
    }

    fun styleExtras(isBrowsable: Boolean, style: Int = STYLE_LIST): Bundle = Bundle().apply {
        putBoolean(STYLE_SUPPORTED, true)
        putInt(BROWSABLE_HINT, if (isBrowsable) style else STYLE_LIST)
        putInt(PLAYABLE_HINT, STYLE_LIST)
    }

    fun libraryParams(params: MediaLibraryService.LibraryParams?, childStyle: Int = STYLE_LIST): MediaLibraryService.LibraryParams =
        MediaLibraryService.LibraryParams.Builder()
            .setOffline(params?.isOffline ?: false)
            .setRecent(params?.isRecent ?: false)
            .setSuggested(params?.isSuggested ?: false)
            .setExtras(Bundle(params?.extras ?: Bundle()).apply {
                putBoolean(STYLE_SUPPORTED, true)
                putInt(BROWSABLE_HINT, childStyle)
                putInt(PLAYABLE_HINT, STYLE_LIST)
            })
            .build()

    fun childStyle(parentId: String): Int =
        if (parentId in gridParentIds || parentId.startsWith("home:")) STYLE_GRID else STYLE_LIST

    fun applyChildStyle(item: MediaItem, parentId: String): MediaItem {
        val metadata = item.mediaMetadata
        val browsable = metadata.isBrowsable == true
        val style = if (browsable) childStyle(parentId) else STYLE_LIST
        val extras = Bundle(metadata.extras ?: Bundle()).apply {
            putBoolean(STYLE_SUPPORTED, true)
            putInt(BROWSABLE_HINT, style)
            putInt(PLAYABLE_HINT, STYLE_LIST)
        }
        return item.buildUpon().setMediaMetadata(metadata.buildUpon().setExtras(extras).build()).build()
    }
}
