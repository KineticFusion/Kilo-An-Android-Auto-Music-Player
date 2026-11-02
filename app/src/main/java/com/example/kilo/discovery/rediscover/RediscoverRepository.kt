package com.example.kilo.discovery.rediscover

import com.example.kilo.discovery.RediscoverPick

class RediscoverRepository {
    @Volatile private var snapshot: List<RediscoverPick> = emptyList()
    fun save(picks: List<RediscoverPick>) { snapshot = picks.toList() }
    fun load(): List<RediscoverPick> = snapshot
}
