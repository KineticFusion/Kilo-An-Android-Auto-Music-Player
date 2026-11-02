package com.example.kilo.lyrics.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import com.example.kilo.lyrics.models.LyricsLine
import com.example.kilo.lyrics.karaoke.KaraokeEngine
import com.example.kilo.lyrics.karaoke.animateKaraokeWord

@Composable
fun KaraokeLyricsView(line: LyricsLine, positionMs: Long, modifier: Modifier = Modifier) {
    val activeWord = KaraokeEngine.activeWordIndex(line, positionMs)
    val scale = animateKaraokeWord(activeWord >= 0)
    val accent = LocalLyricsAccent.current
    val annotated = buildAnnotatedString {
        if (line.words.isEmpty()) append(line.content)
        else line.words.forEachIndexed { index, word ->
            if (index > 0) append(" ")
            val active = positionMs in word.startMs until word.endMs
            if (active) pushStyle(SpanStyle(color = accent, fontWeight = FontWeight.ExtraBold))
            append(word.word)
            if (active) pop()
        }
    }
    Text(annotated, modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(accent.copy(alpha = 0.10f)).graphicsLayer(scaleX = scale, scaleY = scale).padding(8.dp), color = androidx.compose.ui.graphics.Color.White,
        fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
}
