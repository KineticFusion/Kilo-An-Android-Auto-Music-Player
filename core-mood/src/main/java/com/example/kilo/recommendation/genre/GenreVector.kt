package com.example.kilo.recommendation.genre

data class GenreVector(
    val key:String,
    /** Numeric genre DNA; display labels are used only to select this predefined vector. */
    val values:Map<String,Float>,
    val genreWeights:Map<String,Float> = mapOf(key to 100f)
) {
    companion object {
        fun of(label:String):GenreVector {
            val key=label.trim().lowercase().replace('_',' ').replace('-',' ')
            return GenreVector(key,GENRE_FACTORS[key].orEmpty(),mapOf(key to 100f)+(RELATED[key].orEmpty().associateWith{70f}))
        }

        private val GENRE_FACTORS=mapOf(
            "synthwave" to mapOf("electronic" to 95f,"night" to 82f,"nostalgia" to 76f,"driving" to 74f,"dreaminess" to 66f,"tempo" to 58f),
            "retrowave" to mapOf("electronic" to 92f,"night" to 80f,"nostalgia" to 84f,"driving" to 70f,"dreaminess" to 62f),
            "darkwave" to mapOf("electronic" to 86f,"darkness" to 86f,"night" to 82f,"melancholy" to 66f,"atmosphere" to 72f),
            "hip hop" to mapOf("urban" to 88f,"bassIntensity" to 82f,"danceability" to 76f,"vocalDensity" to 76f,"energy" to 70f),
            "rap" to mapOf("urban" to 85f,"bassIntensity" to 78f,"danceability" to 72f,"vocalDensity" to 84f,"energy" to 72f),
            "edm" to mapOf("electronic" to 95f,"danceability" to 88f,"energy" to 84f,"bassIntensity" to 78f,"tempo" to 78f),
            "electronic" to mapOf("electronic" to 92f,"danceability" to 72f,"energy" to 68f,"tempo" to 66f),
            "pop" to mapOf("brightness" to 68f,"uplift" to 70f,"danceability" to 68f,"vocalDensity" to 72f),
            "rock" to mapOf("energy" to 78f,"aggression" to 70f,"brightness" to 58f,"driving" to 68f),
            "indie" to mapOf("acoustic" to 60f,"dreaminess" to 62f,"energy" to 54f,"warmth" to 64f),
            "r&b" to mapOf("warmth" to 74f,"vocalDensity" to 80f,"emotion" to 72f,"danceability" to 68f),
            "jazz" to mapOf("acoustic" to 78f,"instrumental" to 70f,"warmth" to 74f,"energy" to 44f),
            "classical" to mapOf("acoustic" to 88f,"instrumental" to 92f,"cinematic" to 78f,"energy" to 38f),
            "phonk" to mapOf("electronic" to 68f,"bassIntensity" to 92f,"darkness" to 78f,"energy" to 82f,"urban" to 74f),
            "lofi" to mapOf("focus" to 82f,"study" to 84f,"instrumental" to 78f,"energy" to 28f,"warmth" to 72f),
            "ambient" to mapOf("atmosphere" to 96f,"dreaminess" to 90f,"focus" to 70f,"energy" to 20f,"instrumental" to 76f),
            "tamil" to mapOf("regionalAffinity" to 92f,"emotion" to 62f),
            "telugu" to mapOf("regionalAffinity" to 90f,"emotion" to 62f),
            "hindi" to mapOf("regionalAffinity" to 88f,"emotion" to 58f),
            "kannada" to mapOf("regionalAffinity" to 88f,"emotion" to 58f),
            "malayalam" to mapOf("regionalAffinity" to 88f,"emotion" to 58f),
            "punjabi" to mapOf("regionalAffinity" to 90f,"energy" to 65f)
        )
        private val RELATED=mapOf(
            "synthwave" to setOf("retrowave","electronic","darkwave"),"retrowave" to setOf("synthwave","electronic"),
            "hip hop" to setOf("rap","r&b"),"rap" to setOf("hip hop","phonk"),
            "edm" to setOf("electronic","house","dance"),"indie" to setOf("alternative","indie pop"),
            "lofi" to setOf("ambient","jazz")
        )
    }
}
