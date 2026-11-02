package com.example.kilo.ui.layout

/** Window classes are derived from the current app window, never from a device brand. */
enum class DeviceClass {
    COMPACT_PHONE,
    STANDARD_PHONE,
    LARGE_PHONE,
    FOLDABLE_FOLDED,
    FOLDABLE_EXPANDED,
    TABLET,
    LANDSCAPE_TABLET,
    DESKTOP_MODE,
    ANDROID_AUTO
}

enum class FoldState { UNKNOWN, FOLDED, HALF_OPENED, FLAT }

enum class WindowOrientation { PORTRAIT, LANDSCAPE }
