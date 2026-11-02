package com.example.kilo.recommendation.genre

object GenreExpansionEngine {
    /** Keep adjacent genres in the same factor space; no label/title text matching. */
    fun expanded(lock:GenreLock,adjacent:Map<String,Float> = emptyMap()):Map<String,Float> =
        lock.vector.genreWeights + adjacent.mapValues{it.value.coerceIn(0f,35f)}

    fun expand(lock:GenreLock,adjacent:Map<String,Float> = emptyMap()):GenreLock =
        lock.copy(vector=lock.vector.copy(genreWeights=expanded(lock,adjacent)))
}
