package com.example.kilo

enum class AudioQualityPreset(
    val label: String,
    val sourceValue: String,
    private val estimatedBytesPerMinute: Long?,
    val requiresLosslessSource: Boolean = false
) {
    AUTO("Auto", "LOSSLESS", null),
    DATA_SAVER("Data Saver", "DATA_SAVER", 900_000L),
    STANDARD("Standard", "STANDARD", 1_800_000L),
    HIGH("High", "HIGH", 3_500_000L),
    VERY_HIGH("Very High", "VERY_HIGH", 5_500_000L),
    LOSSLESS("Lossless", "LOSSLESS", 8_000_000L, requiresLosslessSource = true),
    HI_RES_LOSSLESS("Hi-Res Lossless", "HI_RES_LOSSLESS", 20_000_000L, requiresLosslessSource = true);

    fun estimatedBytes(durationMs: Long): Long? = estimatedBytesPerMinute?.let { perMinute ->
        (durationMs.coerceAtLeast(0L) * perMinute / 60_000L).coerceAtLeast(1L)
    }

    companion object {
        fun fromPreference(value: String?): AudioQualityPreset = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true) || it.label.equals(value, ignoreCase = true)
        } ?: AUTO

        fun sizeLabel(bytes: Long): String = when {
            bytes >= 1_000_000_000L -> "~${"%.1f".format(java.util.Locale.ROOT, bytes / 1_000_000_000.0)} GB"
            bytes >= 1_000_000L -> "~${"%.0f".format(java.util.Locale.ROOT, bytes / 1_000_000.0)} MB"
            else -> "~${"%.0f".format(java.util.Locale.ROOT, bytes / 1_000.0)} KB"
        }
    }
}
