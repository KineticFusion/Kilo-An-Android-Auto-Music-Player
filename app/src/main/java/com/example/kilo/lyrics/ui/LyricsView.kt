package com.example.kilo.lyrics.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kilo.lyrics.models.LyricsLine

@Composable
fun LyricsView(line: LyricsLine, active: Boolean, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    Text(
        line.content,
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (active) LocalLyricsAccent.current.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = line.startMs >= 0L) { onSeek(line.startMs) }
            .padding(horizontal = 18.dp, vertical = if (active) 12.dp else 8.dp),
        color = if (active) LocalLyricsAccent.current else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.62f),
        fontSize = if (active) 25.sp else 18.sp,
        fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
        textAlign = TextAlign.Center,
        lineHeight = if (active) 34.sp else 27.sp
    )
}
