package com.example.kilo.discovery.social.circles

import com.example.kilo.SongItemClean
import com.example.kilo.discovery.discoveryKey

class CircleRadio(private val generator: CircleMixGenerator = CircleMixGenerator()) {
    fun start(profiles: List<com.example.kilo.companion.CompanionProfile>, catalog: List<SongItemClean>): List<SongItemClean> =
        generator.generate(profiles, catalog, 20).distinctBy(::discoveryKey)
}
