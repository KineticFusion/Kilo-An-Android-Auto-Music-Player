package com.example.kilo.notification

import android.graphics.Bitmap
import android.graphics.Color

/** Fast approximate artwork color for the OS media notification and lockscreen card. */
object DynamicPaletteExtractor {
    fun dominantColor(bitmap: Bitmap?, fallback: Int = Color.rgb(68, 48, 102)): Int {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return fallback
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        val stepX = (bitmap.width / 16).coerceAtLeast(1)
        val stepY = (bitmap.height / 16).coerceAtLeast(1)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) < 80) continue
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count++
            }
        }
        if (count == 0L) return fallback
        val r = (red / count).toInt()
        val g = (green / count).toInt()
        val b = (blue / count).toInt()
        // Lift very dark averages so the system's media controls retain contrast.
        return Color.rgb(maxOf(r, 28), maxOf(g, 28), maxOf(b, 36))
    }
}
