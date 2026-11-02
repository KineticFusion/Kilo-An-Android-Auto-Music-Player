package com.example.kilo.recommendation.mood

import com.example.kilo.mood.MoodRecommendationEngine

object MoodExpansionEngine {
    /** Small numeric expansion; the originally selected factor profile stays dominant. */
    fun expand(lock:MoodLock,phase:Int):MoodVector {
        val relatedNames=(lock.vector.relatedKeys+MoodGraph().related(lock.vector.key)).distinct()
        val relatedProfiles=relatedNames.map{MoodRecommendationEngine.profile(it)}
        val relatedFactors=relatedProfiles.flatMap{profile->profile.attributes.entries.map{(key,value)->canonicalFactor(key) to value}}
            .groupBy({it.first},{it.second}).mapValues{(_,values)->values.average().toFloat()}
        val relatedGenres=relatedProfiles.flatMap{it.genres.entries}
            .groupBy({it.key.trim().lowercase().replace('_',' ').replace('-',' ')},{it.value*100f})
            .mapValues{(_,values)->values.average().toFloat()}
        val explorationBlend=if(phase>=3).12f else 0f
        val shift=when(phase){0->-2f;1->0f;2->4f;3->2f;else->-5f}
        val factors=lock.vector.factors.mapValues{(key,value)->
            val related=relatedFactors[key]
            val blended=if(related!=null) value*(1f-explorationBlend)+related*explorationBlend else value
            when(key){"energy","tempo","danceability"->(blended+shift).coerceIn(0f,100f);else->blended}
        }
        val genres=if(explorationBlend>0f) lock.vector.genres.toMutableMap().apply{
            relatedGenres.forEach{(key,value)->put(key,maxOf(this[key]?:0f,value*.20f))}
        } else lock.vector.genres
        return lock.vector.copy(factors=factors,genres=genres,relatedKeys=relatedNames.toSet())
    }

    private fun canonicalFactor(name:String)=MoodVector.canonicalFactor(name)
}
