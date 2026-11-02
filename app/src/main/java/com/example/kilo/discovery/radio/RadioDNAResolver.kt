package com.example.kilo.discovery.radio

import com.example.kilo.SongItemClean
import com.example.kilo.discovery.PlaylistDNA
import com.example.kilo.discovery.smartshuffle.PlaylistAnalyzer

class RadioDNAResolver(private val analyzer: PlaylistAnalyzer = PlaylistAnalyzer()) {
    fun resolve(seed: List<SongItemClean>): PlaylistDNA = analyzer.analyze(seed, "playlist_radio")
}
