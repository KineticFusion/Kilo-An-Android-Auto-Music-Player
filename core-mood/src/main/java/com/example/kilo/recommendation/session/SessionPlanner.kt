package com.example.kilo.recommendation.session

import com.example.kilo.recommendation.genome.TrackGenome
import com.example.kilo.recommendation.ranking.QueueRanker
import com.example.kilo.recommendation.ranking.RankingRequest
import com.example.kilo.recommendation.ranking.RankedGenome

object SessionPlanner {
    fun plan(candidates:List<TrackGenome>,state:SessionState,request:RankingRequest,limit:Int=20):List<RankedGenome> =
        QueueRanker.rank(candidates,request.copy(session=state),limit)
}
