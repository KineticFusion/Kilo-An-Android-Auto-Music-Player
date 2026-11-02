package com.example.kilo.voice.library

import com.example.kilo.AppStateModel
import com.example.kilo.SongItemClean

class DownloadResolver {
    fun resolve(state: AppStateModel): List<SongItemClean> = state.downloadedSongs
        .filter { it.isDownloaded || it.videoId.isNotBlank() }
        .distinctBy { it.videoId.ifBlank { it.id } }
}
