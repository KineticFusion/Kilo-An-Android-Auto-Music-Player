package com.example.kilo.voice.library

import com.example.kilo.AppStateModel
import com.example.kilo.SavedPlaylist
import com.example.kilo.SongItemClean

class LibraryVoiceResolver(private val downloads: DownloadResolver = DownloadResolver()) {
    fun playlists(state: AppStateModel): List<SavedPlaylist> = state.savedPlaylists
    fun downloads(state: AppStateModel): List<SongItemClean> = downloads.resolve(state)
}
