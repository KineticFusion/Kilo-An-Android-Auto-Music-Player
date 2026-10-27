package com.example.kilo.sourceprefetch

import androidx.media3.common.Player

/** Read-only queue look-ahead. Resolution and cache writes remain with KILO's current path. */
object SourcePrefetchManager {
    fun upcomingIds(player: Player, limit: Int): List<String> {
        if (limit <= 0 || player.mediaItemCount < 2 || player.repeatMode == Player.REPEAT_MODE_ONE) return emptyList()
        val count = player.mediaItemCount
        val current = player.currentMediaItemIndex
        if (current !in 0 until count) return emptyList()
        val ids = LinkedHashSet<String>()
        var offset = 1
        while (offset < count && ids.size < limit) {
            val id = runCatching { player.getMediaItemAt((current + offset) % count).mediaId }.getOrNull()
            if (!id.isNullOrBlank() && !id.startsWith("radio:")) ids.add(id)
            offset++
        }
        return ids.toList()
    }
}
