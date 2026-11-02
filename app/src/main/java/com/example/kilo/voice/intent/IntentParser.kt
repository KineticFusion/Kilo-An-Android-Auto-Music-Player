package com.example.kilo.voice.intent

import com.example.kilo.voice.domain.VoiceCommand
import com.example.kilo.voice.domain.VoiceIntent
import java.util.Locale

/** Deterministic local grammar. No network, model, Gemini key, or AI call is involved. */
object IntentParser {
    private fun clean(raw: String): String = raw.trim().lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}:.'’ -]"), " ")
        .replace(Regex("\\s+"), " ")
        .removePrefix("hey kilo ").removePrefix("okay kilo ").removePrefix("ok kilo ")
        .removeSuffix(" on kilo").removeSuffix(" from kilo").trim()

    private fun command(raw: String, intent: VoiceIntent, entity: String? = null, secondary: String? = null,
                        amountMs: Long? = null, enabled: Boolean? = null) =
        VoiceCommand(raw.trim(), intent, entity?.trim()?.takeIf(String::isNotBlank), secondary?.trim()?.takeIf(String::isNotBlank), amountMs, enabled)

    fun parse(raw: String): VoiceCommand {
        val text = clean(raw)
        if (text.isBlank()) return command(raw, VoiceIntent.UNKNOWN)

        Regex("^(?:please )?(?:create|make|build) (?:me )?(?:a )?(?:new )?playlist(?:\\s+(?:called|named|titled|for|with name)|\\s*[:,-])?\\s+(.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.CREATE_PLAYLIST, it.groupValues[1]) }
        Regex("^(?:please )?(?:create|make|build) (?:me )?(?:a )?(.+?) playlist$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.CREATE_PLAYLIST, it.groupValues[1]) }
        if (text in setOf(
                "create a playlist", "create a new playlist", "create new playlist", "create playlist", "create me a playlist",
                "make a playlist", "make a new playlist", "make new playlist", "make playlist", "make me a playlist",
                "build a playlist", "build me a playlist", "new playlist"
            )) {
            return command(raw, VoiceIntent.CREATE_PLAYLIST)
        }
        Regex("^(?:please )?new playlist (?:called|named|for) (.+)$").matchEntire(text)?.let {
            return command(raw, VoiceIntent.CREATE_PLAYLIST, it.groupValues[1])
        }
        Regex("^(?:please )?new playlist (.+)$").matchEntire(text)?.let {
            return command(raw, VoiceIntent.CREATE_PLAYLIST, it.groupValues[1])
        }
        Regex("^(?:please )?rename (?:my )?playlist (.+?) (?:to|as) (.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.RENAME_PLAYLIST, it.groupValues[1], it.groupValues[2]) }
        Regex("^(?:please )?(?:delete|remove) (?:my )?playlist (.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.DELETE_PLAYLIST, it.groupValues[1]) }
        Regex("^(?:please )?(?:duplicate|copy) (?:my )?playlist (.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.DUPLICATE_PLAYLIST, it.groupValues[1]) }
        Regex("^(?:please )?(?:add|put|save) (?:this|the current|current) (?:song|track) (?:to|in|into) (?:my )?(?:playlist )?(.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.ADD_TO_PLAYLIST, it.groupValues[1]) }
        Regex("^(?:please )?(?:add|put|save) (?:the )?(?:song|track) (.+?) (?:to|in|into) (?:my )?playlist (.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.ADD_TO_PLAYLIST, it.groupValues[2], it.groupValues[1]) }
        Regex("^(?:please )?(?:add|put|save) (?:it|that) (?:to|in|into) (?:my )?playlist (.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.ADD_TO_PLAYLIST, it.groupValues[1]) }
        Regex("^(?:please )?(?:add|put|save) (.+?) (?:to|in|into) (?:my )?playlist (.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.ADD_TO_PLAYLIST, it.groupValues[2], it.groupValues[1]) }
        Regex("^(?:please )?remove (?:this|the current|current) (?:song|track) from (?:my )?(?:playlist )?(.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.REMOVE_FROM_PLAYLIST, it.groupValues[1]) }
        Regex("^(?:please )?remove (?:the )?(?:song|track) (.+?) from (?:my )?playlist (.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.REMOVE_FROM_PLAYLIST, it.groupValues[2], it.groupValues[1]) }
        Regex("^(?:please )?remove (?:it|that) from (?:my )?playlist (.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.REMOVE_FROM_PLAYLIST, it.groupValues[1]) }
        Regex("^(?:please )?remove (.+?) from (?:my )?playlist (.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.REMOVE_FROM_PLAYLIST, it.groupValues[2], it.groupValues[1]) }
        Regex("^(?:please )?(?:download|save offline) (?:all (?:tracks|songs) (?:from|in) )?(?:my )?playlist (.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.DOWNLOAD_PLAYLIST, it.groupValues[1]) }
        Regex("^(?:please )?(?:save|add) (?:the )?playlist (.+?)(?: (?:to|in) (?:my )?library)?$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.SAVE_PLAYLIST, it.groupValues[1]) }
        Regex("^(?:please )?play (?:my )?downloaded playlist (.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.PLAY_DOWNLOADED_PLAYLIST, it.groupValues[1]) }
        Regex("^play something like (.+)$").matchEntire(text)?.let {
            return command(raw, VoiceIntent.PLAY_SIMILAR, it.groupValues[1])
        }
        Regex("^(?:please )?(?:play|start|open) (?:my |a )?(?:saved )?playlist(?:\\s+(?:called|named))? (.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.PLAY_PLAYLIST, it.groupValues[1]) }
        Regex("^(?:please )?(?:play|start|build) (.+?) radio$")
            .matchEntire(text)?.let { match ->
                val station = match.groupValues[1].takeUnless { it in setOf("my", "a", "the") }
                return command(raw, VoiceIntent.BUILD_RADIO, station)
            }

        when (text) {
            "pause", "pause music", "pause the music", "pause playback", "pause song", "stop music", "hold the music" -> return command(raw, VoiceIntent.PAUSE)
            "resume", "resume music", "resume playback", "continue", "continue music", "keep playing", "start playing again", "play it", "play this song" -> return command(raw, VoiceIntent.RESUME)
            "skip", "next", "next song", "next track", "skip song", "skip track", "play the next song" -> return command(raw, VoiceIntent.SKIP)
            "previous", "previous song", "previous track", "go back", "play previous", "play the previous song" -> return command(raw, VoiceIntent.PREVIOUS)
            "skip forward", "go forward", "fast forward" -> return command(raw, VoiceIntent.SEEK_FORWARD, amountMs = 30_000L)
            "rewind", "go back in the song", "rewind the song" -> return command(raw, VoiceIntent.SEEK_BACKWARD, amountMs = 30_000L)
            "play", "play music", "start music", "continue playing" -> return command(raw, VoiceIntent.RESUME)
            "open library", "show library", "my library" -> return command(raw, VoiceIntent.OPEN_LIBRARY)
            "play library", "play my library" -> return command(raw, VoiceIntent.PLAY_LIBRARY)
            "play my liked songs", "play liked songs", "play my likes", "play songs i liked", "play songs i like" -> return command(raw, VoiceIntent.PLAY_LIKED_SONGS)
            "play my favourites", "play my favorites", "play favourites", "play favorites" -> return command(raw, VoiceIntent.PLAY_FAVOURITES)
            "play my downloads", "play downloads", "play downloaded songs", "play offline songs" -> return command(raw, VoiceIntent.PLAY_DOWNLOADS)
            "open downloads", "show downloads", "offline music" -> return command(raw, VoiceIntent.OPEN_DOWNLOADS)
            "play recently added", "play my recently added songs", "play recently added songs" -> return command(raw, VoiceIntent.PLAY_RECENTLY_ADDED)
            "play most played", "play my most played songs" -> return command(raw, VoiceIntent.PLAY_MOST_PLAYED)
            "play least played", "play my least played songs" -> return command(raw, VoiceIntent.PLAY_LEAST_PLAYED)
            "play recent playlist", "play my recent playlist", "play my most recent playlist", "play last playlist", "play the last playlist" -> return command(raw, VoiceIntent.PLAY_RECENT_PLAYLIST)
            "show queue", "open queue", "what's next", "what is next", "show what's next", "up next" -> return command(raw, VoiceIntent.SHOW_QUEUE)
            "clear queue", "clear my queue" -> return command(raw, VoiceIntent.CLEAR_QUEUE)
            "shuffle queue", "shuffle my queue" -> return command(raw, VoiceIntent.SHUFFLE_QUEUE, enabled = true)
            "repeat", "repeat all", "repeat playlist" -> return command(raw, VoiceIntent.REPEAT_ALL)
            "repeat queue", "repeat my queue", "loop queue" -> return command(raw, VoiceIntent.REPEAT_QUEUE)
            "repeat one", "repeat this song", "repeat current track", "repeat this track", "loop this song" -> return command(raw, VoiceIntent.REPEAT_ONE)
            "repeat off", "turn repeat off", "stop repeating" -> return command(raw, VoiceIntent.REPEAT_OFF)
            "shuffle", "shuffle on", "shuffle my songs" -> return command(raw, VoiceIntent.SHUFFLE, enabled = true)
            "mute", "mute music", "mute the music" -> return command(raw, VoiceIntent.MUTE)
            "unmute", "unmute music", "turn sound back on" -> return command(raw, VoiceIntent.UNMUTE)
            "surprise me", "pick something for me", "choose for me" -> return command(raw, VoiceIntent.SURPRISE_ME)
            "play new music", "play something new", "new music" -> return command(raw, VoiceIntent.PLAY_NEW_MUSIC)
            "play hidden gems", "hidden gems" -> return command(raw, VoiceIntent.PLAY_HIDDEN_GEMS)
            "play discovery", "play discovery mix", "discover music" -> return command(raw, VoiceIntent.PLAY_DISCOVERY)
            "start radio", "play radio", "build radio", "start my radio" -> return command(raw, VoiceIntent.BUILD_RADIO)
            "open radio", "show radio" -> return command(raw, VoiceIntent.OPEN_RADIO)
            "open browse", "show browse", "browse music" -> return command(raw, VoiceIntent.OPEN_BROWSE)
            "open home", "go home", "home" -> return command(raw, VoiceIntent.OPEN_HOME)
            "open search", "search music" -> return command(raw, VoiceIntent.OPEN_SEARCH)
            "open settings", "show settings", "open preferences" -> return command(raw, VoiceIntent.OPEN_SETTINGS)
            "open artists", "show artists", "my artists" -> return command(raw, VoiceIntent.OPEN_ARTISTS)
        }

        Regex("^(?:please )?shuffle (?:my )?(?:saved )?playlist(?:\\s+(?:called|named))? (.+)$")
            .matchEntire(text)?.let { return command(raw, VoiceIntent.PLAY_PLAYLIST, it.groupValues[1], enabled = true) }

        Regex("^(?:seek|jump|skip|go) (?:forward|ahead)(?: by)? (\\d+) (sec|secs|second|seconds|min|mins|minute|minutes)$")
            .matchEntire(text)?.let {
                val factor = if (it.groupValues[2].startsWith("min") || it.groupValues[2].startsWith("minute")) 60_000L else 1_000L
                return command(raw, VoiceIntent.SEEK_FORWARD, amountMs = it.groupValues[1].toLongOrNull()?.times(factor))
            }
        Regex("^(?:seek|jump|go|rewind) (?:back|backward)(?: by)? (\\d+) (sec|secs|second|seconds|min|mins|minute|minutes)$")
            .matchEntire(text)?.let {
                val factor = if (it.groupValues[2].startsWith("min") || it.groupValues[2].startsWith("minute")) 60_000L else 1_000L
                return command(raw, VoiceIntent.SEEK_BACKWARD, amountMs = it.groupValues[1].toLongOrNull()?.times(factor))
            }
        Regex("^seek to (\\d+):(\\d{1,2})$").matchEntire(text)?.let {
            val ms = (it.groupValues[1].toLongOrNull() ?: 0L) * 60_000L + (it.groupValues[2].toLongOrNull() ?: 0L) * 1_000L
            return command(raw, VoiceIntent.SEEK_TO, amountMs = ms)
        }
        Regex("^(?:(?:turn|switch) )?(?:shuffle|shuffle mode) (on|off)$").matchEntire(text)?.let {
            return command(raw, VoiceIntent.SHUFFLE, enabled = it.groupValues[1] == "on")
        }
        Regex("^(?:turn|switch) (on|off) shuffle$").matchEntire(text)?.let {
            return command(raw, VoiceIntent.SHUFFLE, enabled = it.groupValues[1] == "on")
        }
        Regex("^(?:add|play) (.+?) (?:next|to the queue|to my queue)$").matchEntire(text)?.let {
            return command(raw, VoiceIntent.ADD_NEXT, it.groupValues[1])
        }
        Regex("^(?:add) (.+?) to (?:the )?queue$").matchEntire(text)?.let {
            return command(raw, VoiceIntent.ADD_TO_QUEUE, it.groupValues[1])
        }
        Regex("^(?:move|put) (.+?) to (?:the )?(top|front|bottom|end) of (?:the )?queue$").matchEntire(text)?.let {
            return command(raw, if (it.groupValues[2] in setOf("top", "front")) VoiceIntent.MOVE_TO_TOP else VoiceIntent.MOVE_TO_BOTTOM, it.groupValues[1])
        }
        Regex("^(?:remove|delete) (.+?) from (?:the )?queue$").matchEntire(text)?.let {
            return command(raw, VoiceIntent.REMOVE_TRACK, it.groupValues[1])
        }
        Regex("^(?:search for|search|find) (.+)$").matchEntire(text)?.let {
            return command(raw, VoiceIntent.SEARCH, it.groupValues[1])
        }
        Regex("^(?:play|start) (.+)$").matchEntire(text)?.let { match ->
            val entity = match.groupValues[1]
            return command(raw, VoiceIntent.PLAY, entity)
        }
        return command(raw, IntentClassifier.classify(text), text.takeUnless { IntentClassifier.classify(text) == VoiceIntent.UNKNOWN })
    }
}
