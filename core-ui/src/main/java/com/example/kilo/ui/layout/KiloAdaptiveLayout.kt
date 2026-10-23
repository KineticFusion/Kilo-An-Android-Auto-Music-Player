package com.example.kilo.ui.layout

enum class KiloWindowClass { Compact, Medium, Expanded }

object AdaptiveLayoutManager {
    fun windowClass(widthDp: Int): KiloWindowClass = when {
        widthDp < 600 -> KiloWindowClass.Compact
        widthDp < 840 -> KiloWindowClass.Medium
        else -> KiloWindowClass.Expanded
    }

    fun gridColumns(widthDp: Int, compactColumns: Int = 2): Int = when (windowClass(widthDp)) {
        KiloWindowClass.Compact -> compactColumns.coerceAtLeast(1)
        KiloWindowClass.Medium -> (compactColumns + 1).coerceAtLeast(2)
        KiloWindowClass.Expanded -> (compactColumns + 2).coerceAtLeast(3)
    }
}
