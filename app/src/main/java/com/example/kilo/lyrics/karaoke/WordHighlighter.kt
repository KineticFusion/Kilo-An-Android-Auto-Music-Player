package com.example.kilo.lyrics.karaoke

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier

@Composable
fun animateKaraokeWord(active: Boolean): Float = animateFloatAsState(
    targetValue = if (active) 1.02f else 1f,
    animationSpec = tween(durationMillis = KaraokeAnimator.HIGHLIGHT_DURATION_MS), label = "karaoke-word"
).value

@Composable
fun karaokeWordModifier(active: Boolean): Modifier {
    val scale = animateKaraokeWord(active)
    return Modifier.graphicsLayer(scaleX = scale, scaleY = scale)
}
