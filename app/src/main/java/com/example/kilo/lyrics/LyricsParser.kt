package com.example.kilo.lyrics

import com.example.kilo.LyricLine
import com.example.kilo.lyrics.models.LyricsLine
import com.example.kilo.lyrics.models.WordTimestamp

object LyricsParser {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun parseLrc(source: String, durationMs: Long = 0L): List<LyricsLine> {
        val rows = source.lineSequence().flatMap { raw ->
            val matches = timestamp.findAll(raw).toList()
            val text = timestamp.replace(raw, "").trim()
            matches.asSequence().mapNotNull { match ->
                val minute = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val second = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val fraction = match.groupValues[3].let { value ->
                    when (value.length) {
                        0 -> 0L
                        1 -> value.toLongOrNull()?.times(100L) ?: 0L
                        2 -> value.toLongOrNull()?.times(10L) ?: 0L
                        else -> value.toLongOrNull() ?: 0L
                    }
                }
                if (text.isBlank()) null else LyricsLine((minute * 60 + second) * 1000 + fraction, -1L, text)
            }
        }.sortedBy { it.startMs }.toList()
        return withLineEnds(rows, durationMs)
    }

    fun fromProvider(lines: List<LyricLine>, durationMs: Long = 0L): List<LyricsLine> =
        withLineEnds(lines.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }.map {
            LyricsLine(
                startMs = it.timeMs,
                endMs = it.endTimeMs,
                content = it.text.trim(),
                words = it.words
            )
        }, durationMs)

    fun parsePlain(source: String): List<LyricsLine> = source.lineSequence()
        .map(String::trim).filter(String::isNotEmpty)
        .map { LyricsLine(-1L, -1L, it) }.toList()

    /** Word timing is estimated inside each synced line when a provider supplies line timing only. */
    fun withEstimatedWords(lines: List<LyricsLine>): List<LyricsLine> = lines.map { line ->
        if (line.startMs < 0L || line.content.isBlank() || line.words.isNotEmpty()) return@map line
        val tokens = Regex("\\S+").findAll(line.content).map { it.value }.toList()
        if (tokens.isEmpty()) return@map line
        val span = (line.endMs - line.startMs).takeIf { it > 0L }
            ?: (tokens.size * 350L).coerceIn(700L, 6_000L)
        val weights = tokens.map { it.length.coerceAtLeast(1).toDouble() }
        val total = weights.sum().coerceAtLeast(1.0)
        var cursor = line.startMs
        val words = tokens.mapIndexed { index, word ->
            val width = if (index == tokens.lastIndex) line.startMs + span - cursor
                else (span * (weights[index] / total)).toLong().coerceAtLeast(80L)
            WordTimestamp(word, cursor, (cursor + width).coerceAtMost(line.startMs + span), estimated = true).also { cursor = it.endMs }
        }
        line.copy(words = words)
    }

    private fun withLineEnds(lines: List<LyricsLine>, durationMs: Long): List<LyricsLine> =
        lines.mapIndexed { index, line ->
            val nextStart = lines.getOrNull(index + 1)?.startMs ?: durationMs.takeIf { it > line.startMs }
            val end = line.endMs.takeIf { it > line.startMs }
                ?: nextStart?.takeIf { it > line.startMs }
                ?: (line.startMs + 4_000L)
            line.copy(endMs = end)
        }
}
