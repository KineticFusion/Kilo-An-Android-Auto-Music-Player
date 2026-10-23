package com.example.kilo.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import com.example.kilo.ui.animation.KiloAnimationSystem

object KiloNavigationSystem {
    fun enter(reduceMotion: Boolean): EnterTransition = KiloAnimationSystem.screenEnter(reduceMotion)
    fun exit(reduceMotion: Boolean): ExitTransition = KiloAnimationSystem.screenExit(reduceMotion)
}
