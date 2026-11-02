package com.example.kilo.recommendation.session

import android.content.Context
import com.example.kilo.recommendation.genre.GenreLock
import com.example.kilo.recommendation.genre.GenreVector
import com.example.kilo.recommendation.mood.FactorLock
import com.example.kilo.recommendation.mood.MoodLock
import com.example.kilo.recommendation.mood.MoodVector
import com.example.kilo.recommendation.radio.SessionRefiller
import com.example.kilo.recommendation.user.UserDNA
import org.json.JSONArray
import org.json.JSONObject

/** Persistent lock and recent queue memory; it contains IDs and vectors, never song display text. */
object SessionMemory {
    private const val PREF="kilo_genome_x_sessions"
    private var cachedRaw:String?=null
    private var cachedState:SessionState?=null
    @Synchronized fun activate(context:Context,sessionId:String,mode:SessionMode,lockKey:String,factory:()->SessionState):SessionState{
        val old=current(context)
        val fresh=factory()
        val recovered=if(old!=null&&old.id==sessionId&&old.mode==mode)SessionRecovery.recover(old,lockKey) else null
        if(old!=null&&recovered==null)SessionRefiller.clear(old)
        val next=recovered?.copy(moodLock=fresh.moodLock,genreLock=fresh.genreLock,factorLock=fresh.factorLock)?:fresh
        save(context,next);return next
    }
    @Synchronized fun current(context:Context):SessionState?{
        val raw=context.applicationContext.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString("active",null)
        if(raw==cachedRaw)return cachedState
        cachedRaw=raw
        cachedState=raw?.let(::decode)
        return cachedState
    }
    @Synchronized fun save(context:Context,state:SessionState){
        val raw=encode(state)
        context.applicationContext.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString("active",raw).apply()
        cachedRaw=raw;cachedState=state
    }
    @Synchronized fun reserve(context:Context,ids:Collection<String>){
        val s=current(context)?:return
        save(context,s.copy(queuedTrackIds=(s.queuedTrackIds+ids).distinct().takeLast(200),updatedAtMs=System.currentTimeMillis()))
    }
    @Synchronized fun nextGeneration(context:Context){
        val s=current(context)?:return
        save(context,s.copy(generation=s.generation+1,phase=SessionEvolution.phase(s.playedCount),updatedAtMs=System.currentTimeMillis()))
    }
    @Synchronized fun updateLockState(context:Context,state:com.example.kilo.recommendation.mood.MoodLockState){
        val current=current(context)?:return
        if(current.lockState!=state)save(context,current.copy(lockState=state,updatedAtMs=System.currentTimeMillis()))
    }
    @Synchronized fun updateProfile(context:Context,userDNA:UserDNA,contextFactors:Map<String,Float>){
        val current=current(context)?:return
        save(context,current.copy(userDNA=userDNA,contextFactors=contextFactors,updatedAtMs=System.currentTimeMillis()))
    }
    @Synchronized fun record(context:Context,trackId:String,artist:String,album:String,genre:String){
        val s=current(context)?:return
        if(trackId.isBlank()||s.trackHistory.lastOrNull()==trackId)return
        save(context,s.copy(playedCount=s.playedCount+1,phase=SessionEvolution.phase(s.playedCount+1),
            trackHistory=(s.trackHistory+trackId).takeLast(100),queuedTrackIds=s.queuedTrackIds.filterNot{it==trackId},
            artistHistory=(s.artistHistory+artist).takeLast(100),albumHistory=(s.albumHistory+album).takeLast(100),
            genreHistory=(s.genreHistory+genre).takeLast(100),updatedAtMs=System.currentTimeMillis()))
    }
    @Synchronized fun end(context:Context){
        context.applicationContext.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().remove("active").apply()
        cachedRaw=null;cachedState=null
        SessionRefiller.clear()
    }
    private fun encode(s:SessionState)=JSONObject().put("id",s.id).put("mode",s.mode.name).put("lock",s.lockKey)
        .put("lockState",s.lockState.name).put("phase",s.phase.name).put("played",s.playedCount).put("generation",s.generation)
        .put("tracks",JSONArray(s.trackHistory)).put("queued",JSONArray(s.queuedTrackIds))
        .put("artists",JSONArray(s.artistHistory)).put("albums",JSONArray(s.albumHistory)).put("genres",JSONArray(s.genreHistory))
        .put("moodLock",encodeMoodLock(s.moodLock)).put("genreLock",encodeGenreLock(s.genreLock))
        .put("factorLock",encodeFactorLock(s.factorLock)).put("userDNA",encodeUserDNA(s.userDNA))
        .put("contextFactors",encodeFloatMap(s.contextFactors))
        .put("started",s.startedAtMs).put("updated",s.updatedAtMs).toString()
    private fun decode(raw:String):SessionState?=runCatching{
        val j=JSONObject(raw)
        fun strings(key:String):List<String>{val a=j.optJSONArray(key)?:return emptyList();return (0 until a.length()).map{a.optString(it)}}
        SessionState(j.optString("id"),SessionMode.valueOf(j.optString("mode")),j.optString("lock"),
            moodLock=decodeMoodLock(j.optJSONObject("moodLock")),genreLock=decodeGenreLock(j.optJSONObject("genreLock")),
            factorLock=decodeFactorLock(j.optJSONObject("factorLock")),
            lockState=runCatching{com.example.kilo.recommendation.mood.MoodLockState.valueOf(j.optString("lockState","LOCKED"))}
                .getOrDefault(com.example.kilo.recommendation.mood.MoodLockState.LOCKED),
            phase=SessionPhase.valueOf(j.optString("phase","WARMUP")),playedCount=j.optInt("played"),generation=j.optInt("generation"),
            trackHistory=strings("tracks"),queuedTrackIds=strings("queued"),artistHistory=strings("artists"),
            albumHistory=strings("albums"),genreHistory=strings("genres"),startedAtMs=j.optLong("started"),updatedAtMs=j.optLong("updated"),
            userDNA=decodeUserDNA(j.optJSONObject("userDNA")),contextFactors=decodeFloatMap(j.optJSONObject("contextFactors")))
    }.getOrNull()

    private fun encodeMoodLock(lock:MoodLock?):JSONObject?=lock?.let{JSONObject()
        .put("key",it.vector.key).put("factors",encodeFloatMap(it.vector.factors)).put("genres",encodeFloatMap(it.vector.genres))
        .put("related",JSONArray(it.vector.relatedKeys)).put("strictness",it.strictness).put("relatedFloor",it.relatedFloor)}
    private fun decodeMoodLock(j:JSONObject?):MoodLock?=j?.let{MoodLock(
        MoodVector(it.optString("key"),decodeFloatMap(it.optJSONObject("factors")),decodeFloatMap(it.optJSONObject("genres")),
            it.optJSONArray("related")?.let{a->(0 until a.length()).map{a.optString(it)}.toSet()}.orEmpty()),
        it.optDouble("strictness",.75).toFloat(),it.optDouble("relatedFloor",.58).toFloat())}
    private fun encodeGenreLock(lock:GenreLock?):JSONObject?=lock?.let{JSONObject()
        .put("key",it.vector.key).put("values",encodeFloatMap(it.vector.values)).put("weights",encodeFloatMap(it.vector.genreWeights))
        .put("strictness",it.strictness).put("relatedFloor",it.relatedFloor)}
    private fun decodeGenreLock(j:JSONObject?):GenreLock?=j?.let{GenreLock(
        GenreVector(it.optString("key"),decodeFloatMap(it.optJSONObject("values")),decodeFloatMap(it.optJSONObject("weights"))),
        it.optDouble("strictness",.75).toFloat(),it.optDouble("relatedFloor",.56).toFloat())}
    private fun encodeFactorLock(lock:FactorLock?):JSONObject?=lock?.let{JSONObject().put("key",it.key)
        .put("factors",encodeFloatMap(it.factors)).put("strictness",it.strictness).put("relatedFloor",it.relatedFloor)}
    private fun decodeFactorLock(j:JSONObject?):FactorLock?=j?.let{FactorLock(it.optString("key"),decodeFloatMap(it.optJSONObject("factors")),
        it.optDouble("strictness",.80).toFloat(),it.optDouble("relatedFloor",.60).toFloat())}

    private fun encodeUserDNA(user:UserDNA)=JSONObject()
        .put("artist",encodeFloatMap(user.artistAffinity)).put("genre",encodeFloatMap(user.genreAffinity))
        .put("language",encodeFloatMap(user.languageAffinity)).put("mood",encodeFloatMap(user.moodAffinity))
        .put("factor",encodeFloatMap(user.factorAffinity)).put("liked",JSONArray(user.likedTrackIds.take(1_000)))
        .put("excluded",JSONArray(user.excludedTrackIds.take(1_000))).put("discovery",user.discoveryPreference)
        .put("languageShare",user.preferredLanguageShare).put("clusters",JSONArray(user.tasteClusterTrackIds.take(100)))
    private fun decodeUserDNA(j:JSONObject?):UserDNA=j?.let{UserDNA(
        artistAffinity=decodeFloatMap(it.optJSONObject("artist")),genreAffinity=decodeFloatMap(it.optJSONObject("genre")),
        languageAffinity=decodeFloatMap(it.optJSONObject("language")),moodAffinity=decodeFloatMap(it.optJSONObject("mood")),
        factorAffinity=decodeFloatMap(it.optJSONObject("factor")),likedTrackIds=decodeStrings(it.optJSONArray("liked")),
        excludedTrackIds=decodeStrings(it.optJSONArray("excluded")),discoveryPreference=it.optDouble("discovery",50.0).toFloat(),
        preferredLanguageShare=it.optDouble("languageShare",85.0).toFloat(),tasteClusterTrackIds=decodeStrings(it.optJSONArray("clusters"))
    )}?:UserDNA()
    private fun encodeFloatMap(values:Map<String,Float>)=JSONObject().apply{values.entries.take(250).forEach{(key,value)->put(key,value.toDouble())}}
    private fun decodeFloatMap(j:JSONObject?):Map<String,Float> = j?.let{obj->buildMap{
        val keys=obj.keys();while(keys.hasNext()){val key=keys.next();put(key,obj.optDouble(key).toFloat())}
    }}.orEmpty()
    private fun decodeStrings(a:JSONArray?):Set<String> = a?.let{(0 until it.length()).map{index->it.optString(index)}.filter(String::isNotBlank).toSet()}.orEmpty()
}
