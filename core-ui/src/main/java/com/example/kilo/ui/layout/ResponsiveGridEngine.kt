package com.example.kilo.ui.layout

object ResponsiveGridEngine {
    fun columns(widthDp: Int, minimumCardWidthDp: Int = 188, maximumColumns: Int = 6): Int {
        if (widthDp <= 0) return 1
        return (widthDp / minimumCardWidthDp.coerceAtLeast(1)).coerceIn(1, maximumColumns.coerceAtLeast(1))
    }

    fun cardWidthDp(widthDp: Int, columns: Int, horizontalPaddingDp: Int, gapDp: Int): Int {
        val count = columns.coerceAtLeast(1)
        val usableWidth = (widthDp - horizontalPaddingDp * 2 - gapDp * (count - 1)).coerceAtLeast(count * 112)
        return usableWidth / count
    }
}
