package com.example.kilo.startup

/** Names and sequences the non-blocking startup phases around KILO's existing player. */
object StartupOrchestrator {
    fun beginPhaseA() {
        LaunchProfiler.begin()
        DeferredWarmupManager.begin()
        LaunchProfiler.mark("phase_a_ui_player_navigation_theme")
    }

    fun scheduleDeferredPhases(phaseB: () -> Unit, phaseC: () -> Unit) {
        DeferredWarmupManager.schedule(phaseB, phaseC)
    }

    fun playerReady() = LaunchProfiler.mark("player_ready")
    fun mediaSessionReady() = LaunchProfiler.mark("media_session_ready")
    fun resolverReady() = LaunchProfiler.mark("resolver_ready")

    fun shutdown() {
        LaunchProfiler.mark("startup_orchestrator_shutdown")
    }
}
