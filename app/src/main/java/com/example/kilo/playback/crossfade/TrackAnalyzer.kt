package com.example.kilo.playback.crossfade

object TrackAnalyzer {
    fun genreHint(metadata: String): String? {
        val value = metadata.lowercase()
        return when {
            listOf("classical", "symphony", "sonata", "orchestra").any(value::contains) -> "classical"
            listOf("edm", "electronic", "techno", "house", "trance", "dance").any(value::contains) -> "edm"
            listOf("hip-hop", "hip hop", "rap", "trap").any(value::contains) -> "hip-hop"
            listOf("rock", "metal", "punk").any(value::contains) -> "rock"
            listOf("acoustic", "unplugged", "folk").any(value::contains) -> "acoustic"
            listOf("jazz", "blues").any(value::contains) -> "jazz"
            listOf("pop", "radio edit").any(value::contains) -> "pop"
            else -> null
        }
    }
}
