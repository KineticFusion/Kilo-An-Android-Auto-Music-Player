package com.example.kilo.ui.layout

enum class KiloWindowClass { Compact, Medium, Expanded }

object AdaptiveLayoutManager {
    fun windowClass(widthDp: Int): KiloWindowClass = when (AdaptiveLayoutEngine.profile(
        WindowSizeResolver.resolve(widthDp, 800, density = 1f)
    ).deviceClass) {
        DeviceClass.COMPACT_PHONE, DeviceClass.STANDARD_PHONE -> KiloWindowClass.Compact
        DeviceClass.LARGE_PHONE, DeviceClass.FOLDABLE_FOLDED -> KiloWindowClass.Medium
        else -> KiloWindowClass.Expanded
    }

    fun gridColumns(widthDp: Int, compactColumns: Int = 2): Int = when (windowClass(widthDp)) {
        KiloWindowClass.Compact -> compactColumns.coerceAtLeast(1)
        KiloWindowClass.Medium -> (compactColumns + 1).coerceAtLeast(2)
        KiloWindowClass.Expanded -> (compactColumns + 2).coerceAtLeast(3)
    }
}
