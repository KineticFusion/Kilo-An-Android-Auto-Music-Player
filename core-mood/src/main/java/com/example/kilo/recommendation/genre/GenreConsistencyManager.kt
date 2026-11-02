package com.example.kilo.recommendation.genre

import com.example.kilo.recommendation.genome.TrackGenome

object GenreConsistencyManager {
    fun match(g:TrackGenome,lock:GenreLock):Float = GenreLockEngine.match(g,lock)
    fun isStrict(g:TrackGenome,lock:GenreLock):Boolean = match(g,lock)>=lock.strictness
    fun isCompatible(g:TrackGenome,lock:GenreLock):Boolean = match(g,lock)>=lock.relatedFloor
    fun accepts(g:TrackGenome,lock:GenreLock,poolSize:Int):Boolean = isCompatible(g,lock)
}
