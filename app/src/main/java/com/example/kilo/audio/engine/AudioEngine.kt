package com.example.kilo.audio.engine

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.Player
import com.example.kilo.audio.profiles.SoundProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/** Application-scoped sound wrapper around KILO's current player and audio session. */
object AudioEngine {
    private const val TAG = "KiloAudioEngine"
    private val mutableState = MutableStateFlow(AudioEngineState())
    val state: StateFlow<AudioEngineState> = mutableState.asStateFlow()

    @Volatile private var sessionManager: AudioSessionManager? = null
    @Volatile private var repository: SoundProfileRepository? = null
    @Volatile private var worker: HandlerThread? = null
    @Volatile private var handler: Handler? = null

    @Synchronized
    fun initialize(context: Context, player: Player) {
        if (sessionManager != null) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
        val profiles = SoundProfileRepository(prefs)
        val thread = HandlerThread("kilo-audio-engine").apply { start() }
        val audioHandler = Handler(thread.looper)
        worker = thread
        handler = audioHandler
        repository = profiles
        val executor = Executor { command -> audioHandler.post(command) }
        sessionManager = AudioSessionManager(app, player, audioHandler, executor, profiles) { next ->
            mutableState.value = next
        }.also { it.start() }
        Log.i(TAG, "initialized around existing Media3 audio session")
    }

    fun selectProfile(context: Context, profileId: String) {
        val prefs = context.applicationContext.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
        val catalog = repository ?: SoundProfileRepository(prefs).also { repository = it }
        catalog.select(profileId)
        handler?.post { sessionManager?.refresh() }
        if (handler == null) {
            val profile = SoundProfileRepository.profiles.firstOrNull { it.id == profileId } ?: return
            mutableState.value = mutableState.value.copy(
                selectedProfileId = profile.id,
                activeProfileTitle = profile.title,
                message = if (profile.id == SoundProfileRepository.ORIGINAL) "Original tone with a level boost; peak limiting applies during playback" else "Start playback to apply this sound preset"
            )
        }
    }
}
