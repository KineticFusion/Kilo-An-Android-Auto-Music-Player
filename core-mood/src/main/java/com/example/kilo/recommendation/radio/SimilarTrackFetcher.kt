package com.example.kilo.recommendation.radio

import com.example.kilo.recommendation.genome.SimilarityGraph
import com.example.kilo.recommendation.genome.TrackGenome

object SimilarTrackFetcher {
    fun fetch(seed:TrackGenome,candidates:Collection<TrackGenome>,limit:Int=20)=candidates
        .asSequence().filter{it.trackId!=seed.trackId}
        .map{it to SimilarityGraph.similarity(seed,it)}.sortedByDescending{it.second}.take(limit).map{it.first}.toList()
}
