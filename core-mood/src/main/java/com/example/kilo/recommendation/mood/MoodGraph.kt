package com.example.kilo.recommendation.mood

/** Expands a target mood only through its explicit adjacent factor profile. */
class MoodGraph(private val links:Map<String,Set<String>> = DEFAULT_LINKS) {
    fun related(key:String)=links[key.trim().lowercase()].orEmpty()

    companion object {
        private val DEFAULT_LINKS=mapOf(
            "night drive" to setOf("chill","dreamy","late night"),
            "focus" to setOf("study","ambient","instrumental"),
            "study" to setOf("focus","lofi","ambient"),
            "relax" to setOf("chill","ambient","acoustic"),
            "workout" to setOf("hype","high energy","dance"),
            "road trip" to setOf("driving","adventure","sing along")
        )
    }
}
