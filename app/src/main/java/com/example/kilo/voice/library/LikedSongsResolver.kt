package com.example.kilo.voice.library

import com.example.kilo.Favorites
import com.example.kilo.SongItemClean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LikedSongsResolver {
    suspend fun resolve(): List<SongItemClean> = withContext(Dispatchers.IO) { Favorites.songs() }
}
