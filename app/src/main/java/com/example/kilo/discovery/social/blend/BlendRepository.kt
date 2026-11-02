package com.example.kilo.discovery.social.blend

import com.example.kilo.SongItemClean

class BlendRepository {
    private val blends = linkedMapOf<String, List<SongItemClean>>()
    @Synchronized fun save(id: String, songs: List<SongItemClean>) { blends[id] = songs.take(50) }
    @Synchronized fun load(id: String): List<SongItemClean> = blends[id].orEmpty()
}
