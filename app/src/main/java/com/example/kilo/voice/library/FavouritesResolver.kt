package com.example.kilo.voice.library

import com.example.kilo.SongItemClean

class FavouritesResolver(private val likedSongsResolver: LikedSongsResolver = LikedSongsResolver()) {
    suspend fun resolve(): List<SongItemClean> = likedSongsResolver.resolve()
}
