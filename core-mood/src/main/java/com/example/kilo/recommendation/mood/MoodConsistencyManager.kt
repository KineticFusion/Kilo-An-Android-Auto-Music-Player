package com.example.kilo.recommendation.mood

import com.example.kilo.recommendation.genome.TrackGenome

object MoodConsistencyManager {
    fun match(g:TrackGenome,lock:MoodLock):Float = MoodLockEngine.match(g,lock)
    fun isStrict(g:TrackGenome,lock:MoodLock):Boolean = match(g,lock)>=lock.strictness
    fun isCompatible(g:TrackGenome,lock:MoodLock):Boolean = match(g,lock)>=lock.relatedFloor
    fun accepts(g:TrackGenome,lock:MoodLock,poolSize:Int):Boolean = isCompatible(g,lock)
}
