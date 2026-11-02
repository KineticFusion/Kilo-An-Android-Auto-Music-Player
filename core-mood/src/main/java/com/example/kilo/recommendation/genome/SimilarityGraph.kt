package com.example.kilo.recommendation.genome

import kotlin.math.sqrt

/** Bounded nearest-neighbour index computed solely from numeric track DNA. */
class SimilarityGraph(private val cap:Int=600) {
    private val edges=LinkedHashMap<String,List<Pair<String,Float>>>()
    @Synchronized fun index(genome:TrackGenome,pool:Collection<TrackGenome>){
        edges[genome.trackId]=pool.filter{it.trackId!=genome.trackId}
            .map{it.trackId to similarity(genome,it)}.sortedByDescending{it.second}.take(20)
        while(edges.size>cap)edges.remove(edges.keys.first())
    }
    @Synchronized fun similar(id:String,limit:Int=20)=edges[id].orEmpty().take(limit.coerceIn(0,20)).map{it.first}
    companion object {
        fun similarity(a:TrackGenome,b:TrackGenome)=factorSimilarity(a,b)*.72f+
            vectorSimilarity(a.genres,b.genres)*.28f
        fun factorSimilarity(genome:TrackGenome,target:Map<String,Float>):Float {
            val observed=target.mapNotNull{(key,wanted)->
                val confidence=genome.evidence(key)
                if(confidence<=0f)null else (kotlin.math.abs(genome.factor(key)-wanted) to confidence)
            }
            if(observed.isEmpty())return 0f
            val fit=1f-observed.sumOf{(delta,confidence)->(delta*confidence).toDouble()}/
                observed.sumOf{it.second.toDouble()}/100.0
            val coverage=(observed.sumOf{it.second.toDouble()}/target.size).coerceIn(0.0,1.0)
            return (fit*kotlin.math.sqrt(coverage)).toFloat().coerceIn(0f,1f)
        }
        private fun factorSimilarity(a:TrackGenome,b:TrackGenome):Float {
            val common=(a.factorConfidence.keys intersect b.factorConfidence.keys)
            if(common.isEmpty())return 0f
            val weights=common.map{it to minOf(a.evidence(it),b.evidence(it))}
            val total=weights.sumOf{it.second.toDouble()}
            if(total<=0.0)return 0f
            val mean=weights.sumOf{(key,weight)->(kotlin.math.abs(a.factor(key)-b.factor(key))*weight).toDouble()}/total
            val coverage=(total/minOf(a.factorConfidence.size,b.factorConfidence.size).coerceAtLeast(1)).coerceIn(0.0,1.0)
            return ((1.0-mean/100.0)*kotlin.math.sqrt(coverage)).toFloat().coerceIn(0f,1f)
        }
        fun factorSimilarity(a:Map<String,Float>,b:Map<String,Float>):Float{
            val keys=a.keys intersect b.keys;if(keys.isEmpty())return 0f
            return (1f-keys.map{key->kotlin.math.abs((a[key]?:50f)-(b[key]?:50f))}.average().toFloat()/100f)
                .coerceIn(0f,1f)
        }
        fun vectorSimilarity(a:Map<String,Float>,b:Map<String,Float>):Float{
            if(a.isEmpty()||b.isEmpty())return 0f
            val keys=a.keys union b.keys;var dot=0.0;var aa=0.0;var bb=0.0
            keys.forEach{k->val x=(a[k]?:0f).toDouble();val y=(b[k]?:0f).toDouble();dot+=x*y;aa+=x*x;bb+=y*y}
            return if(aa==0.0||bb==0.0)0f else (dot/(sqrt(aa)*sqrt(bb))).toFloat().coerceIn(0f,1f)
        }
    }
}
