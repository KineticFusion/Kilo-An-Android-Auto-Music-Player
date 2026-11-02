package com.example.kilo.recommendation.session

import com.example.kilo.recommendation.genre.GenreLock
import com.example.kilo.recommendation.mood.FactorLock
import com.example.kilo.recommendation.mood.MoodLock
import com.example.kilo.recommendation.mood.MoodLockState
import com.example.kilo.recommendation.user.UserDNA

enum class SessionMode { MOOD, GENRE, VIBE, RADIO }
enum class SessionPhase { WARMUP, CORE, PEAK, EXPLORE, RELAXATION }

data class SessionState(
    val id:String,
    val mode:SessionMode,
    val lockKey:String,
    val moodLock:MoodLock?=null,
    val genreLock:GenreLock?=null,
    val factorLock:FactorLock?=null,
    val lockState:MoodLockState=MoodLockState.LOCKED,
    val phase:SessionPhase=SessionPhase.WARMUP,
    val playedCount:Int=0,
    val generation:Int=0,
    val trackHistory:List<String> = emptyList(),
    val queuedTrackIds:List<String> = emptyList(),
    val artistHistory:List<String> = emptyList(),
    val albumHistory:List<String> = emptyList(),
    val genreHistory:List<String> = emptyList(),
    val startedAtMs:Long=System.currentTimeMillis(),
    val updatedAtMs:Long=System.currentTimeMillis(),
    val userDNA:UserDNA=UserDNA(),
    val contextFactors:Map<String,Float> = emptyMap()
)
