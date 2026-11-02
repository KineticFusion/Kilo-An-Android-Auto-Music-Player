package com.example.kilo.recommendation.genre

import com.example.kilo.recommendation.genome.TrackGenome
import com.example.kilo.recommendation.mood.FactorLockEngine

data class GenreLock(val vector:GenreVector,val strictness:Float=.75f,val relatedFloor:Float=.56f)
object GenreLockEngine {
    fun lock(vector:GenreVector,strictness:Float=.75f)=GenreLock(vector,strictness.coerceIn(.75f,.85f),.56f)
    fun match(g:TrackGenome,lock:GenreLock):Float {
        val expanded=GenreExpansionEngine.expanded(lock)
        val tagMatch=expanded.entries.maxOfOrNull{(key,weight)->
            val genreValue=g.genres.entries.firstOrNull{canonical(it.key)==canonical(key)}?.value?:0f
            val languageValue=g.languages.entries.firstOrNull{canonical(it.key)==canonical(key)}?.value?:0f
            maxOf(genreValue,languageValue)/100f*(weight/100f)
        }?:0f
        val hasTagEvidence=g.genres.isNotEmpty()||g.languages.isNotEmpty()
        if(lock.vector.values.isEmpty())return tagMatch.coerceIn(0f,1f)
        val factorLock=FactorLockEngine.lock(lock.vector.key,lock.vector.values,lock.strictness,lock.relatedFloor)
        val factorMatch=FactorLockEngine.match(
            g,factorLock
        )
        val factorCoverage=FactorLockEngine.coverage(g,lock.vector.values)
        val hasFactorEvidence=factorCoverage>0f
        return when {
            hasTagEvidence&&hasFactorEvidence -> {
                val factorWeight=.40f*factorCoverage
                (tagMatch*(1f-factorWeight)+factorMatch*factorWeight).coerceIn(0f,1f)
            }
            hasTagEvidence -> tagMatch.coerceIn(0f,1f)
            else -> factorMatch.coerceIn(0f,1f)
        }
    }

    private fun canonical(value:String)=value.trim().lowercase().replace('_',' ').replace('-',' ')
}
