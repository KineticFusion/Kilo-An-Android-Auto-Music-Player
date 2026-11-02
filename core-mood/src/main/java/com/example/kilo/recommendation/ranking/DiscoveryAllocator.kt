package com.example.kilo.recommendation.ranking

object DiscoveryAllocator {
    /** Picks 70% proven-safe, 20% related and 10% discovery tracks, then backfills sparse buckets. */
    fun allocate(ranked:List<RankedGenome>,limit:Int):List<RankedGenome>{
        if(limit<=0)return emptyList()
        val safe=ranked.filter{it.breakdown.affinity>=65f||it.genome.factor("repeatPotential")>=70f}
        val discovery=ranked.filter{it !in safe&&it.genome.factor("discoveryScore")>=60f}
        val related=ranked.filter{it !in safe&&it !in discovery&&it.breakdown.mood>=60f&&it.breakdown.factor>=55f}
        val safeGoal=(limit*70+99)/100
        val relatedGoal=(limit*20+99)/100
        val out=mutableListOf<RankedGenome>()
        fun take(pool:List<RankedGenome>,n:Int){pool.filter{it !in out}.take((n-out.size).coerceAtLeast(0)).forEach(out::add)}
        take(safe,safeGoal);take(related,safeGoal+relatedGoal);take(discovery,limit)
        take(ranked,limit)
        return out.take(limit)
    }
}
