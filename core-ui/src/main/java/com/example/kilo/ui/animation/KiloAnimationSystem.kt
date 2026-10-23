package com.example.kilo.ui.animation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween

object KiloAnimationSystem {
    const val screenDurationMillis = 220
    const val artworkDurationMillis = 320
    const val moodDurationMillis = 180

    fun screenEnter(reduceMotion: Boolean): EnterTransition = if (reduceMotion) {
        fadeIn(animationSpec = tween(90))
    } else {
        slideInVertically(initialOffsetY = { it / 18 }, animationSpec = tween(screenDurationMillis)) +
            fadeIn(animationSpec = tween(screenDurationMillis))
    }

    fun screenExit(reduceMotion: Boolean): ExitTransition = if (reduceMotion) {
        fadeOut(animationSpec = tween(90))
    } else {
        slideOutVertically(targetOffsetY = { -it / 24 }, animationSpec = tween(screenDurationMillis)) +
            fadeOut(animationSpec = tween(screenDurationMillis))
    }
}
