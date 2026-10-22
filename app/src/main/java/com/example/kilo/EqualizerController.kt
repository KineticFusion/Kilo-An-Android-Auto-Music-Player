package com.example.kilo

import android.media.audiofx.Equalizer
import android.content.SharedPreferences

data class EqualizerBandState(
    val index: Int,
    val centerFrequencyHz: Int,
    val levelMilliBels: Int
)

data class EqualizerSnapshot(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val sessionId: Int = 0,
    val minimumLevelMilliBels: Int = -1500,
    val maximumLevelMilliBels: Int = 1500,
    val bands: List<EqualizerBandState> = emptyList(),
    val presets: List<String> = emptyList(),
    val selectedPreset: Int = -1,
    val message: String = "Start playback to enable the equalizer."
)

/** Controls Android's equalizer effect on Kilo's active Media3 audio session. */
class EqualizerController(private val preferences: SharedPreferences) {
    private var effect: Equalizer? = null
    private var activeSessionId: Int = 0

    @Synchronized
    fun attach(sessionId: Int): EqualizerSnapshot {
        if (sessionId <= 0) return EqualizerSnapshot()
        if (activeSessionId == sessionId && effect != null) return snapshot()
        releaseEffect()
        return try {
            val created = Equalizer(0, sessionId)
            effect = created
            activeSessionId = sessionId
            val count = created.numberOfBands.toInt()
            val stored = preferences.getString("eq_band_levels", null)
                ?.split(',')?.mapNotNull(String::toIntOrNull)
            if (stored != null && stored.size == count) {
                val range = levelRange(created)
                stored.forEachIndexed { index, value ->
                    created.setBandLevel(index.toShort(), value.coerceIn(range[0], range[1]).toShort())
                }
            } else {
                val preset = preferences.getInt("eq_preset", -1)
                if (preset in 0 until created.numberOfPresets.toInt()) created.usePreset(preset.toShort())
            }
            created.enabled = preferences.getBoolean("eq_enabled", false)
            snapshot()
        } catch (error: Throwable) {
            releaseEffect()
            EqualizerSnapshot(message = "This device does not expose an app equalizer: ${error.localizedMessage ?: "audio effect unavailable"}")
        }
    }

    @Synchronized
    fun snapshot(): EqualizerSnapshot {
        val current = effect ?: return EqualizerSnapshot()
        return try {
            val range = levelRange(current)
            val bands = (0 until current.numberOfBands.toInt()).map { index ->
                EqualizerBandState(
                    index = index,
                    centerFrequencyHz = current.getCenterFreq(index.toShort()) / 1000,
                    levelMilliBels = current.getBandLevel(index.toShort()).toInt()
                )
            }
            val presets = (0 until current.numberOfPresets.toInt()).map { current.getPresetName(it.toShort()) }
            EqualizerSnapshot(
                available = current.hasControl(),
                enabled = current.enabled,
                sessionId = activeSessionId,
                minimumLevelMilliBels = range[0],
                maximumLevelMilliBels = range[1],
                bands = bands,
                presets = presets,
                selectedPreset = current.currentPreset.toInt(),
                message = if (current.hasControl()) "Equalizer is connected to the active player." else "Another audio effect currently controls this session."
            )
        } catch (error: Throwable) {
            EqualizerSnapshot(message = error.localizedMessage ?: "Equalizer is unavailable on this device.")
        }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean): EqualizerSnapshot {
        return runCatching {
            effect?.enabled = enabled
            preferences.edit().putBoolean("eq_enabled", enabled).apply()
            snapshot()
        }.getOrElse { snapshot().copy(message = it.localizedMessage ?: "Could not update equalizer.") }
    }

    @Synchronized
    fun setBandLevel(index: Int, levelMilliBels: Int): EqualizerSnapshot {
        return runCatching {
            val current = effect ?: return snapshot()
            val range = levelRange(current)
            current.setBandLevel(index.toShort(), levelMilliBels.coerceIn(range[0], range[1]).toShort())
            preferences.edit().putString(
                "eq_band_levels",
                (0 until current.numberOfBands.toInt()).joinToString(",") {
                    current.getBandLevel(it.toShort()).toString()
                }
            ).putInt("eq_preset", -1).apply()
            snapshot()
        }.getOrElse { snapshot().copy(message = it.localizedMessage ?: "Could not adjust equalizer band.") }
    }

    @Synchronized
    fun applyPreset(index: Int): EqualizerSnapshot {
        return runCatching {
            val current = effect ?: return snapshot()
            if (index !in 0 until current.numberOfPresets.toInt()) return snapshot()
            current.usePreset(index.toShort())
            preferences.edit().putInt("eq_preset", index).remove("eq_band_levels").apply()
            snapshot()
        }.getOrElse { snapshot().copy(message = it.localizedMessage ?: "Could not apply equalizer preset.") }
    }

    @Synchronized
    fun release() = releaseEffect()

    private fun levelRange(equalizer: Equalizer): IntArray = equalizer.bandLevelRange.map { it.toInt() }.toIntArray()

    private fun releaseEffect() {
        runCatching { effect?.release() }
        effect = null
        activeSessionId = 0
    }
}
