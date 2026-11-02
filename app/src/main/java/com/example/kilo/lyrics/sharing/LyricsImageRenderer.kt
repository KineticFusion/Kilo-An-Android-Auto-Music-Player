package com.example.kilo.lyrics.sharing

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

object LyricsImageRenderer {
    fun save(context: Context, bitmap: Bitmap): File {
        val directory = File(context.cacheDir, "lyrics-share").apply { mkdirs() }
        val output = File(directory, "kilo-lyrics-${System.currentTimeMillis()}.png")
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return output
    }
}
