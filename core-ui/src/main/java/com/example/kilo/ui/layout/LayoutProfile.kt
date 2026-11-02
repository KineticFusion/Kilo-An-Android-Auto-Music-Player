package com.example.kilo.ui.layout

data class LayoutProfile(
    val deviceClass: DeviceClass,
    val window: WindowSizeSnapshot,
    val screen: ScreenProfile
) {
    val isPhone: Boolean
        get() = deviceClass in setOf(DeviceClass.COMPACT_PHONE, DeviceClass.STANDARD_PHONE, DeviceClass.LARGE_PHONE, DeviceClass.FOLDABLE_FOLDED)

    val isExpanded: Boolean
        get() = screen.columns >= 3
}
