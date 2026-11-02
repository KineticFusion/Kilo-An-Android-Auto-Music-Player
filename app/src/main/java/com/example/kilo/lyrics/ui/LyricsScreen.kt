package com.example.kilo.lyrics.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import coil.compose.AsyncImage
import com.example.kilo.PlayerHolder
import com.example.kilo.SongItemClean
import com.example.kilo.lyrics.LyricsManager
import com.example.kilo.lyrics.LyricsDownloader
import com.example.kilo.lyrics.LyricsSettings
import com.example.kilo.lyrics.LyricsSynchronizer
import com.example.kilo.lyrics.TranslationManager
import com.example.kilo.lyrics.SyncedLyricsEngine
import com.example.kilo.lyrics.models.Lyrics
import com.example.kilo.lyrics.models.LyricsLine
import com.example.kilo.lyrics.sharing.LyricsCardGenerator
import com.example.kilo.lyrics.sharing.LyricsImageRenderer
import com.example.kilo.lyrics.sharing.ShareManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun LyricsScreen(
    song: SongItemClean,
    initialPositionMs: Long,
    accent: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val player = remember(context) { PlayerHolder.get(context.applicationContext) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var lyrics by remember(song.id) { mutableStateOf<Lyrics?>(LyricsManager.cached(song)) }
    var loading by remember(song.id) { mutableStateOf(lyrics == null) }
    var position by remember(song.id) { mutableStateOf(initialPositionMs) }
    var currentIndex by remember(song.id) { mutableIntStateOf(-1) }
    var seekPreview by remember(song.id) { mutableStateOf<Float?>(null) }
    var translatedLines by remember(song.id) { mutableStateOf<List<LyricsLine>?>(null) }
    val lyricsEnabled = remember(song.id) { LyricsSettings.enabled(context) }
    val karaokeEnabled = remember(song.id) { LyricsSettings.karaoke(context) }
    val autoScrollEnabled = remember(song.id) { LyricsSettings.autoScroll(context) }
    val animatedHighlight = remember(song.id) { LyricsSettings.animatedHighlight(context) }
    val translationEnabled = remember(song.id) { LyricsSettings.translationEnabled(context) }
    val synchronizer = remember { LyricsSynchronizer(50L) }

    LaunchedEffect(song.id, lyricsEnabled) {
        if (!lyricsEnabled) {
            loading = false
            lyrics = null
        } else {
            loading = true
            lyrics = runCatching { LyricsManager.find(song) }.getOrNull()
            loading = false
        }
    }
    LaunchedEffect(lyrics, translationEnabled) {
        val active = lyrics ?: return@LaunchedEffect
        translatedLines = if (translationEnabled && TranslationManager.isAvailable()) {
            runCatching { withContext(Dispatchers.IO) { TranslationManager.translatedLines(active, Locale.getDefault().language) } }.getOrNull()
        } else null
    }
    LaunchedEffect(song.id) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            delay(50L)
        }
    }
    LaunchedEffect(lyrics, song.id) {
        val active = lyrics ?: return@LaunchedEffect
        synchronizer.follow(
            listState = listState,
            indexProvider = { SyncedLyricsEngine.activeLine(active, position) },
            autoScroll = autoScrollEnabled,
            onLineChanged = { currentIndex = it }
        )
    }

    when {
        !lyricsEnabled -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Lyrics are turned off in Playback Settings.", color = Color.White.copy(alpha = 0.72f), textAlign = TextAlign.Center)
        }
        lyrics == null && loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = accent)
        }
        lyrics == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Lyrics aren’t available for this track yet.", color = Color.White.copy(alpha = 0.72f), textAlign = TextAlign.Center)
        }
        lyrics!!.lines.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Lyrics aren’t available for this track yet.", color = Color.White.copy(alpha = 0.72f), textAlign = TextAlign.Center)
        }
        else -> {
            val data = lyrics!!
            val displayLines = translatedLines ?: data.lines
            val duration = player.duration.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET } ?: song.durationMs
            val progress = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Box(modifier.fillMaxSize()) {
                AsyncImage(
                    model = song.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(24.dp).graphicsLayer(alpha = 0.24f)
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC090A10), Color(0xEE101117), Color(0xFF101117)))))
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (karaokeEnabled) "KARAOKE LYRICS" else "LYRICS", color = accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
                            Text("${song.title} · ${song.artist}", color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (translationEnabled && !TranslationManager.isAvailable()) {
                                Text("Translation provider not configured", color = Color.White.copy(alpha = 0.48f), fontSize = 10.sp, maxLines = 1)
                            }
                            if (karaokeEnabled && data.lines.getOrNull(currentIndex)?.words?.let { words -> words.isNotEmpty() && words.all { it.estimated } } == true) {
                                Text("Karaoke timing is approximate for this track", color = Color.White.copy(alpha = 0.48f), fontSize = 10.sp, maxLines = 1)
                            }
                        }
                        IconButton(onClick = {
                            scope.launch {
                                val bitmap = withContext(Dispatchers.IO) {
                                    val line = displayLines.getOrNull(currentIndex)?.content ?: displayLines.first().content
                                    val art = loadArtwork(song.coverUrl)
                                    LyricsCardGenerator.render(song.title, song.artist, line, accent.toArgb(), art).also { art?.recycle() }
                                }
                                val file = withContext(Dispatchers.IO) { LyricsImageRenderer.save(context, bitmap) }
                                ShareManager.share(context, file, song.id, song.title)
                                bitmap.recycle()
                            }
                        }) { Icon(Icons.Default.Share, contentDescription = "Share lyrics card", tint = Color.White) }
                        IconButton(onClick = {
                            scope.launch {
                                val saved = runCatching { LyricsDownloader.saveForOffline(song) }.getOrNull()
                                Toast.makeText(context, if (saved != null) "Lyrics saved for offline use" else "Lyrics couldn’t be saved", Toast.LENGTH_SHORT).show()
                            }
                        }) { Icon(Icons.Default.Download, contentDescription = "Save lyrics offline", tint = Color.White) }
                    }
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        itemsIndexed(displayLines, key = { index, line -> "${song.id}_${line.startMs}_$index" }) { index, line ->
                            val active = index == currentIndex
                            val scale by animateFloatAsState(if (active && animatedHighlight && !karaokeEnabled) 1.02f else 1f, tween(150), label = "lyric-line-scale")
                            val lineModifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .graphicsLayer(scaleX = scale, scaleY = scale)
                                    .clickable(enabled = line.startMs >= 0L) { onSeek(line.startMs) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            if (karaokeEnabled && active) {
                                LyricsTheme(accent) { KaraokeLyricsView(line, position, lineModifier) }
                            } else {
                                LyricsTheme(accent) { LyricsView(line, active, onSeek, lineModifier) }
                            }
                        }
                    }
                    Slider(
                        value = seekPreview ?: progress,
                        onValueChange = { seekPreview = it },
                        onValueChangeFinished = {
                            seekPreview?.let { if (duration > 0L) onSeek((it * duration).toLong()) }
                            seekPreview = null
                        },
                        colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { player.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous track", tint = Color.White) }
                        IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                            Icon(if (player.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (player.isPlaying) "Pause" else "Play", tint = accent)
                        }
                        IconButton(onClick = { player.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, contentDescription = "Next track", tint = Color.White) }
                    }
                }
            }
        }
    }
}

private fun loadArtwork(url: String): android.graphics.Bitmap? = runCatching {
    when {
        url.startsWith("file:") -> BitmapFactory.decodeFile(android.net.Uri.parse(url).path)
        url.startsWith("http://") || url.startsWith("https://") -> {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            try { connection.inputStream.use { BitmapFactory.decodeStream(it) } } finally { connection.disconnect() }
        }
        else -> null
    }
}.getOrNull()

