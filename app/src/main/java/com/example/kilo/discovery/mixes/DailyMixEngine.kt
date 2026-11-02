package com.example.kilo.discovery.mixes

import com.example.kilo.SongItemClean
import com.example.kilo.companion.CompanionContext
import com.example.kilo.companion.ListeningEvent
import com.example.kilo.companion.CompanionProfile
import com.example.kilo.discovery.DiscoveryMix

class DailyMixEngine(private val builder: TimeAwareMixBuilder = TimeAwareMixBuilder()) {
    fun generate(catalog: List<SongItemClean>, events: List<ListeningEvent>, context: CompanionContext, profile: CompanionProfile = CompanionProfile()): List<DiscoveryMix> =
        builder.profiles.map { builder.build(it, catalog, events, context, profile) }
}
