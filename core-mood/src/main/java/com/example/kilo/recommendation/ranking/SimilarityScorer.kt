package com.example.kilo.recommendation.ranking

import com.example.kilo.recommendation.genome.SimilarityGraph
import com.example.kilo.recommendation.genome.TrackGenome

object SimilarityScorer {
    fun score(a:TrackGenome,b:TrackGenome)=SimilarityGraph.similarity(a,b)
    fun factors(genome:TrackGenome,target:Map<String,Float>)=SimilarityGraph.factorSimilarity(genome,target)
    fun factors(a:Map<String,Float>,b:Map<String,Float>)=SimilarityGraph.factorSimilarity(a,b)
}
