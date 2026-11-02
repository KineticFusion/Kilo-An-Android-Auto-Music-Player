package com.example.kilo.lyrics.sharing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.toColorInt

object LyricsCardGenerator {
    fun render(title: String, artist: String, line: String, accent: Int, artwork: Bitmap? = null): Bitmap {
        val width = 1080
        val height = 1350
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF101117.toInt()
        canvas.drawColor(paint.color)
        artwork?.let {
            paint.alpha = 70
            val src = RectF(0f, 0f, it.width.toFloat(), it.height.toFloat())
            canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
            paint.alpha = 255
        }
        paint.color = accent
        canvas.drawRoundRect(RectF(72f, 72f, 1008f, 1278f), 54f, 54f, paint.apply { alpha = 42 })
        paint.alpha = 255
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 42f
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        canvas.drawText("KILO  ·  LYRICS", 112f, 190f, paint)
        paint.textSize = 58f
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        drawWrapped(canvas, line, 112f, 570f, 850f, paint, 78f)
        paint.color = accent
        canvas.drawRoundRect(RectF(112f, 1015f, 230f, 1027f), 8f, 8f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 44f
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        canvas.drawText(title.take(36), 112f, 1110f, paint)
        paint.color = 0xFFCACBD3.toInt()
        paint.textSize = 36f
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        canvas.drawText(artist.take(48), 112f, 1172f, paint)
        return bitmap
    }

    private fun drawWrapped(canvas: Canvas, text: String, x: Float, y: Float, maxWidth: Float, paint: Paint, lineHeight: Float) {
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        text.split(Regex("\\s+")).forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                lines += current.toString(); current = StringBuilder(word)
            } else current = StringBuilder(candidate)
        }
        if (current.isNotEmpty()) lines += current.toString()
        lines.take(5).forEachIndexed { index, value -> canvas.drawText(value, x, y + index * lineHeight, paint) }
    }
}
