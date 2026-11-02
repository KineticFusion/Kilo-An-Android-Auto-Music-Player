package com.example.kilo.recommendation.genome

data class TrackFingerprint(val trackId:String,val signature:List<Int>) {
    companion object {
        fun from(genome:TrackGenome)=TrackFingerprint(genome.trackId,
            genome.factors.toSortedMap().values.map{(it/10f).toInt()}+
                genome.genres.toSortedMap().values.map{(it/10f).toInt()})
    }
}
