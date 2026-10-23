package com.example.kilo

import android.app.Application

class KiloApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PlaybackWarmupManager.initialize(this)
    }
}
