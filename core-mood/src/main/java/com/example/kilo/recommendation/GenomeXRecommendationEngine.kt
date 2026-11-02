package com.example.kilo.recommendation

import android.content.Context
import com.example.kilo.companion.CompanionProfile
import com.example.kilo.companion.ListeningEvent
import com.example.kilo.mood.MoodFeatureProfile
import com.example.kilo.mood.TrackFeatureProfile
import com.example.kilo.recommendation.diagnostics.RecommendationDebugger
import com.example.kilo.recommendation.diagnostics.RecommendationProfiler
import com.example.kilo.recommendation.genome.GenomeBuilder
import com.example.kilo.recommendation.genome.GenomeDatabase
import com.example.kilo.recommendation.genome.TrackGenome
import com.example.kilo.recommendation.genre.GenreLockEngine
import com.example.kilo.recommendation.genre.GenreExpansionEngine
import com.example.kilo.recommendation.genre.GenreVector
import com.example.kilo.recommendation.intelligence.RecommendationContext
import com.example.kilo.recommendation.intelligence.ContextAnalyzer
import com.example.kilo.recommendation.home.HomeFeedEngine
import com.example.kilo.recommendation.mood.MoodLockEngine
import com.example.kilo.recommendation.mood.MoodVector
import com.example.kilo.recommendation.mood.MoodExpansionEngine
import com.example.kilo.recommendation.mood.FactorLock
import com.example.kilo.recommendation.mood.FactorLockEngine
import com.example.kilo.recommendation.radio.InfiniteRadioEngine
import com.example.kilo.recommendation.radio.RadioSessionBuilder
import com.example.kilo.recommendation.radio.SessionRefiller
import com.example.kilo.recommendation.ranking.RankingRequest
import com.example.kilo.recommendation.ranking.RankedGenome
import com.example.kilo.recommendation.session.SessionEvolution
import com.example.kilo.recommendation.session.SessionMemory
import com.example.kilo.recommendation.session.SessionMode
import com.example.kilo.recommendation.session.SessionState
import com.example.kilo.recommendation.user.PreferenceLearner
import com.example.kilo.recommendation.user.UserProfileBuilder

/** Shared facade for phone and Android Auto. All recommendation selection stays above playback. */
object GenomeXRecommendationEngine {
    private data class Pool(val genomes:List<TrackGenome>,val request:RankingRequest)
    private val pools=LinkedHashMap<String,Pool>()

    /** Home uses its dedicated 35/25/15/10/5/5/5 score and never mutates a playback session. */
    @Synchronized fun rankHome(
        context: Context,
        profiles: List<TrackFeatureProfile>,
        albums: Map<String, String> = emptyMap(),
        user: CompanionProfile = CompanionProfile(),
        events: List<ListeningEvent> = emptyList(),
        factorTargets: Map<String, Float> = emptyMap(),
        contextFactors: Map<String, Float> = emptyMap(),
        popularityByTrack: Map<String, Float> = emptyMap(),
        limit: Int = profiles.size
    ): List<String> {
        val startedAt = System.currentTimeMillis()
        if (profiles.isEmpty() || limit <= 0) return emptyList()
        val genomes = profiles.map { profile ->
            val aliases = profile.aliases + profile.trackId
            PreferenceLearner.updateGenome(
                GenomeBuilder.from(profile, albums[profile.trackId].orEmpty()),
                events.filter { it.trackId in aliases },
                aliases
            )
        }.distinctBy { it.trackId }
        GenomeDatabase.put(context, genomes)
        val genomesByAlias = buildMap {
            profiles.forEach { profile ->
                val genome = genomes.firstOrNull { it.trackId == profile.trackId } ?: return@forEach
                (profile.aliases + profile.trackId).forEach { put(it, genome) }
            }
        }
        val userDNA = UserProfileBuilder.build(user, events, genomesByAlias = genomesByAlias)
        val ids = HomeFeedEngine.rank(
            candidates = genomes,
            user = userDNA,
            events = events,
            factorTargets = factorTargets,
            contextFactors = contextFactors,
            popularityByTrack = popularityByTrack,
            limit = limit.coerceAtMost(genomes.size)
        ).map { it.genome.trackId }
        RecommendationProfiler.record(genomes.size, System.currentTimeMillis() - startedAt, ids)
        return ids
    }

    @Synchronized fun rank(
        context:Context,profiles:List<TrackFeatureProfile>,albums:Map<String,String> = emptyMap(),
        mood:MoodFeatureProfile?=null,genre:String?=null,user:CompanionProfile=CompanionProfile(),
        events:List<ListeningEvent> = emptyList(),playedIds:Set<String> = emptySet(),
        driving:Boolean=false,sessionId:String="default",limit:Int=profiles.size,
        @Suppress("UNUSED_PARAMETER") genresByTrack:Map<String,Set<String>> = emptyMap(),
        vibeKey:String?=null,
        factorLock:FactorLock?=null,
        recommendationContext:RecommendationContext?=null
    ):List<String>{
        val start=System.currentTimeMillis()
        if(profiles.isEmpty())return emptyList()
        val genomes=profiles.map{p->
            val aliases=p.aliases+p.trackId
            // Rebuild from current per-track metadata and behavior. Retrieval query labels are
            // deliberately excluded so search provenance cannot masquerade as track DNA.
            val base=GenomeBuilder.from(p,albums[p.trackId].orEmpty())
            PreferenceLearner.updateGenome(base,events.filter{it.trackId in aliases},aliases)
        }.distinctBy{it.trackId}
        GenomeDatabase.put(context,genomes)
        val moodVector=mood?.let(MoodVector::from)
        val genreLock=genre?.takeIf{it.isNotBlank()}?.let{GenreLockEngine.lock(GenreVector.of(it))}
        val activeFactorLock=factorLock ?: vibeKey?.takeIf{it.isNotBlank()}?.let{
            FactorLockEngine.lock(it,mapOf("energy" to 50f,"tempo" to 50f))
        }
        val lockKey=when{
            genreLock!=null->"genre:"+genreLock.vector.key
            moodVector!=null->"mood:"+moodVector.key
            activeFactorLock!=null->"vibe:"+activeFactorLock.key
            else->"discover"
        }
        val mode=when{
            genreLock!=null->SessionMode.GENRE
            moodVector!=null->SessionMode.MOOD
            activeFactorLock!=null->SessionMode.VIBE
            else->SessionMode.RADIO
        }
        var state=SessionMemory.activate(context,sessionId,mode,lockKey){
            if(mode==SessionMode.RADIO)RadioSessionBuilder.create(sessionId,lockKey)
            else SessionState(sessionId,mode,lockKey,moodLock=moodVector?.let(MoodLockEngine::lock),
                genreLock=genreLock,factorLock=activeFactorLock)
        }
        val genomesByAlias=buildMap {
            profiles.forEach{p->val genome=genomes.firstOrNull{it.trackId==p.trackId}?:return@forEach
                (p.aliases+p.trackId).forEach{put(it,genome)}
            }
        }
        val userDNA=UserProfileBuilder.build(user,events,genomesByAlias=genomesByAlias)
        val contextFactors=ContextAnalyzer.factors(recommendationContext?.copy(
            driving=recommendationContext.driving||driving,
            androidAuto=recommendationContext.androidAuto||driving
        ) ?: RecommendationContext(driving=driving,androidAuto=driving))
        state=state.copy(userDNA=userDNA,contextFactors=contextFactors)
        SessionMemory.save(context,state)
        val activeMoodLock=state.moodLock?.let{MoodLockEngine.lock(MoodExpansionEngine.expand(it,state.phase.ordinal))}
        val activeGenreLock=genreLock?.let(GenreExpansionEngine::expand)
        val sessionFactorLock=state.factorLock?.let{FactorLockEngine.lock(it.key,SessionEvolution.factors(it.factors,state.phase),it.strictness,it.relatedFloor)}
        val targetFactors=when{
            activeMoodLock!=null->activeMoodLock.vector.factors
            activeGenreLock!=null->SessionEvolution.factors(activeGenreLock.vector.values,state.phase)
            sessionFactorLock!=null->sessionFactorLock.factors
            else->emptyMap()
        }
        val request=RankingRequest(moodLock=activeMoodLock,genreLock=activeGenreLock,factorLock=sessionFactorLock,
            factors=targetFactors,user=userDNA,session=state,contextFactors=contextFactors,
            playedIds=playedIds+state.trackHistory+state.queuedTrackIds)
        val poolKey=state.id+":"+state.mode.name
        pools[poolKey]=Pool(genomes,request)
        while(pools.size>4)pools.remove(pools.keys.first())
        val ranked=InfiniteRadioEngine.initial(state,genomes,request).take(limit.coerceAtMost(InfiniteRadioEngine.INITIAL_SIZE))
        val ids=ranked.map{it.genome.trackId}
        if(ranked.isNotEmpty()&&(activeMoodLock!=null||activeGenreLock!=null||sessionFactorLock!=null)){
            val average=ranked.map{if(sessionFactorLock!=null)it.breakdown.factor/100f else it.breakdown.mood/100f}.average().toFloat()
            SessionMemory.updateLockState(context,MoodLockEngine.stateFor(average,
                state.moodLock?.strictness?:state.genreLock?.strictness?:state.factorLock?.strictness?:.75f))
        }
        SessionMemory.reserve(context,ids)
        ranked.take(5).forEach(RecommendationDebugger::log)
        RecommendationProfiler.record(genomes.size,System.currentTimeMillis()-start,ids)
        return ids
    }

    /** Produces a 20-item refill while keeping a 50-track ranked lookahead in the session layer. */
    @Synchronized fun refill(context:Context,batchSize:Int=InfiniteRadioEngine.REFILL_SIZE,position:Int=InfiniteRadioEngine.REFILL_AT):List<String>{
        val state=SessionMemory.current(context)?:return emptyList()
        val key=state.id+":"+state.mode.name
        val pool=pools[key]?:restorePool(context,state)?.also{pools[key]=it}?:return emptyList()
        val request=pool.request.copy(session=state,playedIds=pool.request.playedIds+state.trackHistory+state.queuedTrackIds)
        val ranked=InfiniteRadioEngine.refill(state,pool.genomes,request,position)
        val ids=ranked.take(batchSize.coerceIn(1,InfiniteRadioEngine.REFILL_SIZE)).map{it.genome.trackId}
        if(ranked.isNotEmpty()){
            val average=ranked.map{item->when{
                request.factorLock!=null->item.breakdown.factor/100f
                request.moodLock!=null||request.genreLock!=null->item.breakdown.mood/100f
                else->.5f
            }}.average().toFloat()
            val strictness=request.moodLock?.strictness?:request.genreLock?.strictness?:request.factorLock?.strictness?:.75f
            SessionMemory.updateLockState(context,MoodLockEngine.stateFor(average,strictness))
        }
        SessionMemory.reserve(context,ids)
        if(ids.isNotEmpty())SessionMemory.nextGeneration(context)
        return ids
    }

    @Synchronized fun hasRefillPool(context:Context):Boolean{
        val s=SessionMemory.current(context)?:return false
        return pools.containsKey(s.id+":"+s.mode.name)||s.queuedTrackIds.isNotEmpty()
    }

    @Synchronized fun belongsToActiveSession(context:Context,trackId:String):Boolean{
        val s=SessionMemory.current(context)?:return false
        return pools[s.id+":"+s.mode.name]?.genomes?.any{it.trackId==trackId} == true ||
            trackId in s.queuedTrackIds || trackId in s.trackHistory
    }

    fun recordPlayed(context:Context,trackId:String,artistIdentity:String="",albumIdentity:String="",genreIdentity:String="") {
        val state=SessionMemory.current(context)
        val genome=state?.let{pools[it.id+":"+it.mode.name]?.genomes?.firstOrNull{g->g.trackId==trackId}}
        val dominantGenre=genreIdentity.ifBlank{genome?.genres?.maxByOrNull{it.value}?.key.orEmpty()}
        SessionMemory.record(context,trackId,artistIdentity,albumIdentity,dominantGenre)
    }
    @Synchronized fun endSession(context:Context){
        SessionMemory.end(context)
        pools.clear()
    }

    /** Rebuild a lightweight ranking pool from persisted genomes after service/process recreation. */
    private fun restorePool(context:Context,state:SessionState):Pool?{
        val genomes=GenomeDatabase.all(context)
        if(genomes.isEmpty())return null
        val moodLock=state.moodLock?.let{MoodLockEngine.lock(MoodExpansionEngine.expand(it,state.phase.ordinal),it.strictness)}
        val genreLock=state.genreLock?.let(GenreExpansionEngine::expand)
        val factorLock=state.factorLock?.let{FactorLockEngine.lock(
            it.key,SessionEvolution.factors(it.factors,state.phase),it.strictness,it.relatedFloor
        )}
        val targetFactors=when{
            moodLock!=null->moodLock.vector.factors
            genreLock!=null->SessionEvolution.factors(genreLock.vector.values,state.phase)
            factorLock!=null->factorLock.factors
            else->emptyMap()
        }
        val request=RankingRequest(moodLock=moodLock,genreLock=genreLock,factorLock=factorLock,
            factors=targetFactors,user=state.userDNA,session=state,contextFactors=state.contextFactors,
            playedIds=(state.trackHistory+state.queuedTrackIds).toSet())
        return Pool(genomes,request)
    }
}
