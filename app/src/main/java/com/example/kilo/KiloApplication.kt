package com.example.kilo

import android.app.Application
import android.content.Context

class KiloApplication : Application() {
    companion object {
        @Volatile
        lateinit var appContext: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        PlaybackWarmupManager.initialize(this)
    }
}
