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
            .background(if (active) LocalLyricsAccent.current.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = line.startMs >= 0L) { onSeek(line.startMs) }.padding(horizontal = 16.dp, vertical = 8.dp),
        color = if (active) LocalLyricsAccent.current else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.56f),
        fontSize = if (active) 21.sp else 17.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
        textAlign = TextAlign.Center,
        lineHeight = 29.sp
    )
}
