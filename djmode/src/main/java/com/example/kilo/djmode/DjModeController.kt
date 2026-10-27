package com.example.kilo.djmode

import com.example.kilo.audioanalysis.TrackAnalysisEntity
import kotlin.math.abs

data class DjTrack(val id: String, val analysis: TrackAnalysisEntity?)

data class DjModeSettings(
    val enabled: Boolean = false,
    val crossfadeMs: Long = 4_000L,
    val beatMatch: Boolean = false,
    val smartQueue: Boolean = true,
    val energyProgression: Boolean = false
) {
    fun sanitized() = copy(crossfadeMs = crossfadeMs.coerceIn(2_000L, 8_000L))
}

data class DjTransitionPlan(
    val compatible: Boolean,
    val crossfadeMs: Long,
    val playbackSpeed: Float,
    val score: Double
)

/** Pure planner wrapped around the caller's queue; disabled mode returns it unchanged. */
class DjModeController {
    fun planQueue(queue: List<DjTrack>, settings: DjModeSettings): List<DjTrack> {
        if (!settings.enabled || !settings.smartQueue || queue.size < 3) return queue
        val head = queue.first()
        val remaining = queue.drop(1).toMutableList()
        val ordered = mutableListOf(head)
        var previous = head
        while (remaining.isNotEmpty()) {
            val next = remaining.maxByOrNull { score(previous.analysis, it.analysis, settings.energyProgression) }
                ?: remaining.first()
            ordered += next
            remaining.remove(next)
            previous = next
        }
        return ordered
    }

    fun transition(from: TrackAnalysisEntity?, to: TrackAnalysisEntity?, settings: DjModeSettings): DjTransitionPlan {
        val safe = settings.sanitized()
        if (!safe.enabled) return DjTransitionPlan(false, 0L, 1f, 0.0)
        val compatibility = score(from, to, safe.energyProgression)
        val fromBpm = from?.bpm
        val toBpm = to?.bpm
        val speed = if (safe.beatMatch && fromBpm != null && toBpm != null) {
            (fromBpm / toBpm).toFloat().coerceIn(0.97f, 1.03f)
        } else 1f
        return DjTransitionPlan(compatibility >= 0.35, safe.crossfadeMs, speed, compatibility)
    }

    private fun score(from: TrackAnalysisEntity?, to: TrackAnalysisEntity?, energyProgression: Boolean): Double {
        if (from == null || to == null) return 0.0
        val fromBpm = from.bpm
        val toBpm = to.bpm
        val bpmScore = when {
            fromBpm == null || toBpm == null -> 0.35
            else -> {
                val ratio = fromBpm / toBpm
                val normalizedRatio = when {
                    ratio in 0.45..0.55 -> ratio * 2.0
                    ratio in 1.8..2.2 -> ratio / 2.0
                    else -> ratio
                }
                (1.0 - abs(1.0 - normalizedRatio).coerceAtMost(1.0)).coerceIn(0.0, 1.0)
            }
        }
        val fromKey = from.musicalKey
        val toKey = to.musicalKey
        val keyScore = when {
            fromKey.isNullOrBlank() || toKey.isNullOrBlank() -> 0.35
            else -> harmonicKeyScore(fromKey, toKey)
        }
        val energyDelta = abs(from.energy - to.energy) / 100.0
        val energyScore = if (energyProgression) {
            if (to.energy + 0.08 >= from.energy) 1.0 - energyDelta * 0.5 else 0.2
        } else 1.0 - energyDelta
        return bpmScore * 0.42 + keyScore * 0.38 + energyScore * 0.20
    }

    private fun harmonicKeyScore(first: String, second: String): Double {
        val names = listOf("C", "G", "D", "A", "E", "B", "F#", "C#", "G#", "D#", "A#", "F")
        fun parse(value: String): Pair<Int, Boolean>? {
            val root = value.trim().substringBefore(' ').replace("♭", "b").replace("♯", "#")
            val index = names.indexOf(root)
            if (index < 0) return null
            return index to value.contains("minor", true)
        }
        val a = parse(first) ?: return 0.35
        val b = parse(second) ?: return 0.35
        val distance = minOf((a.first - b.first + 12) % 12, (b.first - a.first + 12) % 12)
        return when {
            distance == 0 && a.second == b.second -> 1.0
            distance == 0 -> 0.72
            distance == 1 && a.second == b.second -> 0.78
            distance == 5 && a.second == b.second -> 0.74
            else -> 0.0
        }
    }
}
