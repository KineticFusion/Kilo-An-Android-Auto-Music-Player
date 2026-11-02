package com.example.kilo.ui.layout

/** Screen-level layout choices kept separate from product navigation and playback. */
data class ScreenProfile(
    val columns: Int,
    val horizontalPaddingDp: Int,
    val maxContentWidthDp: Int,
    val minimumCardWidthDp: Int,
    val usePersistentPanels: Boolean,
    val useCompactHeaders: Boolean
)
