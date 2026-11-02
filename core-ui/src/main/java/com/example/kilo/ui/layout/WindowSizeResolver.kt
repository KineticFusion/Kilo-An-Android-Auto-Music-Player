package com.example.kilo.ui.layout

/** A platform-neutral snapshot of the usable app window. */
data class WindowSizeSnapshot(
    val widthDp: Int,
    val heightDp: Int,
    val density: Float,
    val orientation: WindowOrientation,
    val foldState: FoldState = FoldState.UNKNOWN
) {
    val aspectRatio: Float
        get() = if (heightDp > 0) widthDp.toFloat() / heightDp else 1f
}

object WindowSizeResolver {
    fun resolve(
        widthDp: Int,
        heightDp: Int,
        density: Float,
        foldState: FoldState = FoldState.UNKNOWN,
        orientation: WindowOrientation = if (widthDp >= heightDp) WindowOrientation.LANDSCAPE else WindowOrientation.PORTRAIT
    ): WindowSizeSnapshot = WindowSizeSnapshot(
        widthDp = widthDp.coerceAtLeast(0),
        heightDp = heightDp.coerceAtLeast(0),
        density = density.coerceAtLeast(0.1f),
        orientation = orientation,
        foldState = foldState
    )
}
