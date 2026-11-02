package com.example.kilo.discovery.continuation

import com.example.kilo.SongItemClean
import com.example.kilo.discovery.PlaylistDNA
import com.example.kilo.discovery.smartshuffle.PlaylistAnalyzer

class PlaylistDNAAnalyzer(private val analyzer: PlaylistAnalyzer = PlaylistAnalyzer()) {
    fun analyze(songs: List<SongItemClean>, id: String = "active"): PlaylistDNA = analyzer.analyze(songs, id)
}
