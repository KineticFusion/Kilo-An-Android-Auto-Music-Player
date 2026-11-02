package com.example.kilo.recommendation.radio

import com.example.kilo.recommendation.genome.TrackGenome
import com.example.kilo.recommendation.ranking.RankedGenome
import com.example.kilo.recommendation.ranking.RankingRequest
import com.example.kilo.recommendation.session.SessionGenerator
import com.example.kilo.recommendation.session.SessionState

/** Ranks the next 50 from a supplied candidate pool; retrieval stays in the app's existing layer. */
object SessionRefiller {
    private data class Lookahead(val lockKey:String,val tracks:MutableList<RankedGenome>)
    private val lookaheads=LinkedHashMap<String,Lookahead>()
    private const val REPEAT_COOLDOWN=50
    private fun key(state:SessionState)="${state.id}:${state.mode}:${state.lockKey}"

    /** Rank 50 ahead once, hand 20 to the queue, and retain the next 30 in memory. */
    @Synchronized fun initial(state:SessionState,candidates:List<TrackGenome>,request:RankingRequest):List<RankedGenome>{
        val ranked=SessionGenerator.generate(candidates,state,request.copy(session=state),InfiniteRadioEngine.LOOKAHEAD)
        val selected=ranked.take(InfiniteRadioEngine.INITIAL_SIZE)
        val id=key(state)
        lookaheads[id]=Lookahead(state.lockKey,ranked.drop(selected.size).toMutableList())
        while(lookaheads.size>4)lookaheads.remove(lookaheads.keys.first())
        return selected
    }

    /** At item 15, serve 20 cached tracks and refill the ranked lookahead back toward 50. */
    @Synchronized fun refill(
        state:SessionState,candidates:List<TrackGenome>,request:RankingRequest,position:Int,limit:Int=20
    ):List<RankedGenome>{
        if(position<InfiniteRadioEngine.REFILL_AT)return emptyList()
        val id=key(state)
        val buffer=lookaheads[id]?.takeIf{it.lockKey==state.lockKey}?:Lookahead(state.lockKey,mutableListOf()).also{lookaheads[id]=it}
        val sessionExcluded=(state.trackHistory+state.queuedTrackIds).toSet()
        buffer.tracks.removeAll{it.genome.trackId in sessionExcluded}
        fillLookahead(buffer,state,candidates,request)
        val selected=buffer.tracks.take(limit).toList()
        val selectedIds=selected.mapTo(hashSetOf()){it.genome.trackId}
        buffer.tracks.removeAll{it.genome.trackId in selectedIds}
        fillLookahead(buffer,state,candidates,request,selectedIds)
        return selected
    }

    /** Prefer every never-played candidate first; once the local pool is exhausted, cycle only
     * tracks outside a 50-track cooldown. The active lock is retained in both passes. */
    private fun fillLookahead(
        buffer:Lookahead,state:SessionState,candidates:List<TrackGenome>,request:RankingRequest,
        batchExcluded:Set<String> = emptySet()
    ){
        if(buffer.tracks.size>=InfiniteRadioEngine.LOOKAHEAD)return
        val queuedAndBuffered=(state.queuedTrackIds+buffer.tracks.map{it.genome.trackId}+batchExcluded).distinct()
        val freshExcluded=(state.trackHistory+queuedAndBuffered).toSet()
        val fresh=candidates.filterNot{it.trackId in freshExcluded}
        if(fresh.isNotEmpty()){
            val missing=InfiniteRadioEngine.LOOKAHEAD-buffer.tracks.size
            buffer.tracks.addAll(SessionGenerator.generate(
                fresh,state,request.copy(session=state,playedIds=request.playedIds+freshExcluded),missing
            ))
        }
        if(buffer.tracks.size>=InfiniteRadioEngine.LOOKAHEAD)return

        // Keep as much of the 50-track cooldown as this source pool can sustain at the next refill.
        // Small pools get a shorter, bounded cooldown rather than letting the car queue run dry.
        val cooldown=(candidates.size-InfiniteRadioEngine.REFILL_SIZE-state.queuedTrackIds.size)
            .coerceIn(1,REPEAT_COOLDOWN)
        val cooledState=state.copy(
            playedCount=state.trackHistory.takeLast(cooldown).size,
            trackHistory=state.trackHistory.takeLast(cooldown),
            artistHistory=state.artistHistory.takeLast(14),
            albumHistory=state.albumHistory.takeLast(cooldown),
            genreHistory=state.genreHistory.takeLast(cooldown)
        )
        val cycleExcluded=(cooledState.trackHistory+queuedAndBuffered).toSet()
        val cyclic=candidates.filterNot{it.trackId in cycleExcluded}
        if(cyclic.isNotEmpty()){
            val missing=InfiniteRadioEngine.LOOKAHEAD-buffer.tracks.size
            buffer.tracks.addAll(SessionGenerator.generate(
                cyclic,cooledState,request.copy(session=cooledState,playedIds=request.playedIds+cycleExcluded),missing
            ))
        }
    }

    fun next(state:SessionState,candidates:List<TrackGenome>,request:RankingRequest,limit:Int=50):List<RankedGenome> =
        SessionGenerator.generate(candidates,state,request.copy(session=state),limit.coerceAtMost(InfiniteRadioEngine.LOOKAHEAD))

    @Synchronized fun clear(state:SessionState?=null){
        if(state==null)lookaheads.clear() else lookaheads.remove(key(state))
    }
    fun shouldRefill(position:Int,queued:Int)=queued-position<=5
}
