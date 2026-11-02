package com.example.kilo.recommendation.ranking

import com.example.kilo.recommendation.genome.TrackGenome

object QueueRanker {
    fun rank(genomes:List<TrackGenome>,request:RankingRequest,limit:Int=genomes.size)=
        RecommendationRanker.rank(CandidateGenerator.from(genomes,request.user.excludedTrackIds),request,limit)
}
