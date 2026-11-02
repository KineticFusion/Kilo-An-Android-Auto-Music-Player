package com.example.kilo.discovery.social.jam

import com.example.kilo.discovery.JamSession

class SessionExporter {
    fun text(session: JamSession): String = buildString {
        appendLine("${session.name} — KILO listening session")
        appendLine("${session.queue.size} tracks")
        session.queue.forEachIndexed { index, song -> appendLine("${index + 1}. ${song.title} — ${song.artist}") }
    }
}
