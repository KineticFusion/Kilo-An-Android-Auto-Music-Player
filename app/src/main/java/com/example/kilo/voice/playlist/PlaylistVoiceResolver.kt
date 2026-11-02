package com.example.kilo.voice.playlist

import com.example.kilo.SavedPlaylist

class PlaylistVoiceResolver(private val aliases: PlaylistAliasRepository? = null) {
    fun resolve(name: String, playlists: List<SavedPlaylist>): SavedPlaylist? {
        val byName = playlists.associateBy { it.name }
        PlaylistMatcher.bestMatch(name, byName.keys)?.let { return byName[it] }
        val aliasMatch = playlists.firstOrNull { playlist ->
            aliases?.aliases(playlist.id)?.any { PlaylistMatcher.score(name, it) >= 0.75f } == true
        }
        return aliasMatch
    }
}
