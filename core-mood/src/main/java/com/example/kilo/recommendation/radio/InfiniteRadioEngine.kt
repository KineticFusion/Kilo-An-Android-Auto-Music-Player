package com.example.kilo.recommendation.radio

import com.example.kilo.recommendation.genome.TrackGenome
import com.example.kilo.recommendation.ranking.RankedGenome
import com.example.kilo.recommendation.ranking.RankingRequest
import com.example.kilo.recommendation.session.SessionState

/** 20-item initial plans and 20-item refill chunks; caller owns retrieval and current queue. */
object InfiniteRadioEngine {
    const val INITIAL_SIZE=20
    const val REFILL_AT=15
    const val REFILL_SIZE=20
    const val LOOKAHEAD=50
    fun initial(state:SessionState,candidates:List<TrackGenome>,request:RankingRequest):List<RankedGenome> =
        SessionRefiller.initial(state,candidates,request)
    fun refill(state:SessionState,candidates:List<TrackGenome>,request:RankingRequest,position:Int):List<RankedGenome> =
        SessionRefiller.refill(state,candidates,request,position,REFILL_SIZE)
}
