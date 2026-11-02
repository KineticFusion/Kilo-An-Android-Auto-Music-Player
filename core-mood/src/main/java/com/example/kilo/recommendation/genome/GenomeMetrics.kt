package com.example.kilo.recommendation.genome

object GenomeMetrics {
    private var hits=0L;private var misses=0L;private var builtCount=0L;private var low=0L
    @Synchronized fun hit(){hits++}
    @Synchronized fun miss(){misses++}
    @Synchronized fun built(confidence:Float){builtCount++;if(confidence<.35f)low++}
    @Synchronized fun snapshot()=mapOf("hits" to hits,"misses" to misses,"built" to builtCount,"lowConfidence" to low)
}
