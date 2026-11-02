package com.example.kilo.ui.layout

object AdaptiveLayoutEngine {
    fun profile(window: WindowSizeSnapshot): LayoutProfile {
        val width = window.widthDp
        val deviceClass = when {
            window.foldState == FoldState.FOLDED -> DeviceClass.FOLDABLE_FOLDED
            window.foldState == FoldState.FLAT && width >= 840 -> DeviceClass.FOLDABLE_EXPANDED
            width >= 1200 && window.orientation == WindowOrientation.LANDSCAPE -> DeviceClass.DESKTOP_MODE
            width >= 840 && window.orientation == WindowOrientation.LANDSCAPE -> DeviceClass.LANDSCAPE_TABLET
            width >= 840 -> DeviceClass.TABLET
            width >= 600 -> DeviceClass.LARGE_PHONE
            width >= 390 -> DeviceClass.STANDARD_PHONE
            else -> DeviceClass.COMPACT_PHONE
        }

        val columns = when (deviceClass) {
            DeviceClass.COMPACT_PHONE -> 1
            DeviceClass.STANDARD_PHONE -> 2
            DeviceClass.LARGE_PHONE, DeviceClass.FOLDABLE_FOLDED -> 2
            DeviceClass.FOLDABLE_EXPANDED, DeviceClass.TABLET -> 3
            DeviceClass.LANDSCAPE_TABLET -> 4
            DeviceClass.DESKTOP_MODE -> 5
            DeviceClass.ANDROID_AUTO -> 1
        }
        val horizontalPadding = when (deviceClass) {
            DeviceClass.COMPACT_PHONE -> 16
            DeviceClass.STANDARD_PHONE, DeviceClass.LARGE_PHONE, DeviceClass.FOLDABLE_FOLDED -> 20
            DeviceClass.FOLDABLE_EXPANDED, DeviceClass.TABLET -> 28
            DeviceClass.LANDSCAPE_TABLET -> 36
            DeviceClass.DESKTOP_MODE -> 48
            DeviceClass.ANDROID_AUTO -> 16
        }
        val maxWidth = when (deviceClass) {
            DeviceClass.DESKTOP_MODE -> 1440
            DeviceClass.FOLDABLE_EXPANDED, DeviceClass.TABLET, DeviceClass.LANDSCAPE_TABLET -> 1280
            else -> 960
        }

        return LayoutProfile(
            deviceClass = deviceClass,
            window = window,
            screen = ScreenProfile(
                columns = columns,
                horizontalPaddingDp = horizontalPadding,
                maxContentWidthDp = maxWidth,
                minimumCardWidthDp = if (columns >= 4) 200 else 164,
                usePersistentPanels = deviceClass in setOf(DeviceClass.LANDSCAPE_TABLET, DeviceClass.DESKTOP_MODE),
                useCompactHeaders = deviceClass == DeviceClass.COMPACT_PHONE
            )
        )
    }
}
