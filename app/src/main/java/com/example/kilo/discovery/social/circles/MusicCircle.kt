package com.example.kilo.discovery.social.circles

import com.example.kilo.discovery.MusicCircleSnapshot

data class MusicCircle(
    val id: String,
    val name: String,
    val memberProfileIds: List<String>,
    val snapshot: MusicCircleSnapshot? = null
)
