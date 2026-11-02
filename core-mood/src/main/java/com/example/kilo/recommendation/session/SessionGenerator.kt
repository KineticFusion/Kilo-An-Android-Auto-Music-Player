package com.example.kilo.recommendation.session

import com.example.kilo.recommendation.genome.TrackGenome
import com.example.kilo.recommendation.ranking.RankingRequest
import com.example.kilo.recommendation.ranking.RankedGenome

object SessionGenerator {
    fun generate(candidates:List<TrackGenome>,state:SessionState,request:RankingRequest,count:Int=20)=
        SessionPlanner.plan(candidates,state,request,count.coerceIn(1,50))
    fun reachedRefillPoint(position:Int,chunkSize:Int=20)=position>=chunkSize-5
}
