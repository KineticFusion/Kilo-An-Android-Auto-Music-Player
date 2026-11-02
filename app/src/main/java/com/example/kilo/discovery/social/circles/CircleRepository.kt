package com.example.kilo.discovery.social.circles

import com.example.kilo.discovery.MusicCircleSnapshot

class CircleRepository {
    private val circles = linkedMapOf<String, MusicCircleSnapshot>()
    @Synchronized fun save(snapshot: MusicCircleSnapshot) { circles[snapshot.circleId] = snapshot }
    @Synchronized fun all(): List<MusicCircleSnapshot> = circles.values.toList()
}
