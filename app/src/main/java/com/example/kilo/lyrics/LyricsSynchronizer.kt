package com.example.kilo.lyrics

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.delay

class LyricsSynchronizer(private val intervalMs: Long = 50L) {
    suspend fun follow(
        listState: LazyListState,
        indexProvider: () -> Int,
        autoScroll: Boolean = true,
        onLineChanged: (Int) -> Unit = {}
    ) {
        var last = -1
        while (true) {
            val index = indexProvider()
            if (index != last) {
                onLineChanged(index)
            }
            if (autoScroll && index >= 0 && index != last) {
                val centerOffset = -(listState.layoutInfo.viewportSize.height / 2)
                listState.animateScrollToItem(index, scrollOffset = centerOffset)
            }
            if (index != last) {
                last = index
            }
            delay(intervalMs)
        }
    }
}
