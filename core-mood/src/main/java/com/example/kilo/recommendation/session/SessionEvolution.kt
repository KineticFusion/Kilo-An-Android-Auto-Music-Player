package com.example.kilo.recommendation.session

object SessionEvolution {
    fun phase(played:Int)=when{played<3->SessionPhase.WARMUP;played<10->SessionPhase.CORE;
        played<16->SessionPhase.PEAK;played<24->SessionPhase.EXPLORE;else->SessionPhase.RELAXATION}
    fun advance(s:SessionState)=s.copy(phase=phase(s.playedCount),updatedAtMs=System.currentTimeMillis())

    /** Gentle phase shifts shape the journey without weakening its active mood/genre lock. */
    fun factors(base:Map<String,Float>,phase:SessionPhase):Map<String,Float> = base.toMutableMap().apply {
        fun shift(key:String,delta:Float){this[key]=((this[key]?:50f)+delta).coerceIn(0f,100f)}
        when(phase){
            SessionPhase.WARMUP->{shift("energy",-6f);shift("brightness",2f)}
            SessionPhase.CORE->Unit
            SessionPhase.PEAK->{shift("energy",5f);shift("driving",4f)}
            SessionPhase.EXPLORE->{shift("discoveryScore",10f)}
            SessionPhase.RELAXATION->{shift("energy",-12f);shift("tempo",-8f);shift("atmosphere",5f)}
        }
    }
}
