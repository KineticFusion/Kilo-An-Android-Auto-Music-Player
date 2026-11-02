package com.example.kilo.lyrics.sharing

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.kilo.lyrics.LyricsMetrics
import java.io.File

object ShareManager {
    fun share(context: Context, file: File, trackId: String, title: String) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "$title · KILO")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share lyrics"))
            LyricsMetrics.share(trackId, true)
        }.onFailure { LyricsMetrics.share(trackId, false) }
    }
}
