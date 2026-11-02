package com.example.kilo.recommendation.ranking

import com.example.kilo.recommendation.genome.TrackGenome

object CandidateGenerator {
    fun from(genomes:Collection<TrackGenome>,excluded:Set<String> = emptySet())=
        genomes.asSequence().filter{it.trackId.isNotBlank()&&it.trackId !in excluded}.distinctBy{it.trackId}.toList()
}
