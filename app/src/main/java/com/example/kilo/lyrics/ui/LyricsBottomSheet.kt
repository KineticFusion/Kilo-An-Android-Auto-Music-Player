package com.example.kilo.lyrics.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.kilo.SongItemClean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsBottomSheet(song: SongItemClean, positionMs: Long, accent: Color, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color(0xFF111218)) {
        LyricsScreen(song, positionMs, accent, onSeek, Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 720.dp))
    }
}
