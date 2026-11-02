package com.example.kilo.recommendation.genome

import com.example.kilo.mood.TrackFeatureProfile

/** Converts the fused audio/profile data into the stable numeric TrackGenome. */
object GenomeBuilder {
    fun from(
        profile: TrackFeatureProfile,
        album: String = "",
        @Suppress("UNUSED_PARAMETER") retrievalGenreHints: Set<String> = emptySet()
    ): TrackGenome {
        val a=profile.attributes
        fun g(vararg keys:String,fallback:Float=50f)=keys.firstNotNullOfOrNull{a[it]}?:fallback
        val energy=g("energy");val dance=g("danceability");val tempo=g("tempo")
        val inst=g("instrumentalness");val dark=g("darkness");val night=g("nightDrive","night")
        // Only track-level metadata is evidence. A mood/genre search query that surfaced a
        // candidate must never be copied into that track's durable genome as a genre label.
        val genres = profile.genres.mapKeys { normalize(it.key) }.mapValues { it.value * 100f }
        fun genre(vararg keys:String)=keys.maxOfOrNull{k->genres.entries.firstOrNull{it.key==normalize(k)}?.value?:0f}?:0f
        val atmosphere=g("atmosphere",fallback=maxOf(genre("ambient"),genre("soundtrack"))*.8f)
        val factors=mapOf(
            "energy" to energy,"danceability" to dance,"darkness" to dark,
            "brightness" to g("brightness",fallback=100f-dark),
            "driving" to g("driving",fallback=maxOf(night,energy*.55f+tempo*.45f)),
            "focus" to g("focus",fallback=inst*.6f+(100f-energy)*.4f),
            "study" to g("study","focus",fallback=inst*.65f+(100f-tempo)*.35f),
            "workout" to g("workout",fallback=energy*.65f+dance*.35f),
            "night" to night,"lateNight" to g("lateNight",fallback=night),
            "electronic" to maxOf(genre("electronic"),genre("synthwave"),genre("dance")),
            "acoustic" to maxOf(genre("acoustic"),genre("folk")),
            "instrumental" to inst,"vocalDensity" to g("vocalDensity",fallback=100f-inst),
            "bassIntensity" to g("bassIntensity",fallback=energy*.55f+maxOf(genre("hip-hop"),genre("dance"))*.45f),
            "aggression" to g("aggression",fallback=energy*.55f+maxOf(genre("metal"),genre("rock"))*.45f),
            "nostalgia" to g("nostalgia"),"dreaminess" to g("dreaminess",fallback=atmosphere*.78f),
            "atmosphere" to atmosphere,"tempo" to tempo,"emotion" to g("emotion"),
            "melancholy" to g("melancholy"),"uplift" to g("uplift",fallback=energy*.45f+dance*.3f+(100f-dark)*.25f),
            "warmth" to g("warmth",fallback=100f-g("coldness")),"coldness" to g("coldness",fallback=dark*.65f),
            "urban" to g("urban",fallback=maxOf(genre("hip-hop"),genre("electronic"))*.8f),
            "cinematic" to g("cinematic",fallback=maxOf(genre("soundtrack"),atmosphere/100f)*100f),
            "regionalAffinity" to g("regionalAffinity"),
            "eraAffinity" to g("eraAffinity",fallback=profile.replayRate*60f+profile.completionRate*40f),
            "discoveryScore" to g("discoveryScore","discovery",fallback=100f-profile.metadataConfidence*100f),
            "mainstreamIndex" to g("mainstreamIndex","mainstream",fallback=profile.metadataConfidence*50f),
            "repeatPotential" to g("repeatPotential","replay",fallback=profile.replayRate*100f),
            "sessionCompatibility" to g("sessionCompatibility",fallback=(50f+profile.completionRate*30f-profile.skipRate*50f)),
            "familiarity" to g("familiarity",fallback=50f+profile.replayRate*35f+profile.completionRate*15f)
        )
        val explicitFactors = profile.attributes.keys.mapTo(hashSetOf(), ::canonicalFactor)
        val providerGenreEvidence = profile.genres.mapKeys { normalize(it.key) }
            .mapValues { (_, value) -> value.coerceIn(0f, 1f) }
        val factorConfidence = factors.keys.mapNotNull { key ->
            val direct = key in explicitFactors
            val supportGenres = when (key) {
                "electronic" -> setOf("electronic", "synthwave", "dance")
                "acoustic" -> setOf("acoustic", "folk")
                "instrumental" -> setOf("instrumental", "ambient", "classical", "lofi")
                "atmosphere", "dreaminess" -> setOf("ambient", "synthwave", "electronic", "jazz", "soundtrack")
                "energy", "workout" -> setOf("dance", "hip hop", "rock", "ambient", "classical")
                "danceability" -> setOf("dance", "pop", "hip hop")
                "tempo" -> setOf("ambient", "classical", "dance", "hip hop", "synthwave", "pop")
                "darkness", "coldness", "nostalgia", "night", "lateNight" -> setOf("synthwave", "alternative", "ambient")
                "focus", "study" -> setOf("lofi", "ambient", "classical", "instrumental")
                "bassIntensity", "urban" -> setOf("hip hop", "electronic", "dance")
                "aggression", "driving" -> setOf("rock", "synthwave", "electronic")
                "cinematic" -> setOf("soundtrack", "classical")
                "regionalAffinity" -> setOf("tamil", "telugu", "hindi", "kannada", "malayalam", "punjabi")
                else -> emptySet()
            }
            val providerSignal = supportGenres.maxOfOrNull { providerGenreEvidence[it] ?: 0f } ?: 0f
            val languageSignal = if (key == "regionalAffinity") profile.languages.values.maxOrNull() ?: 0f else 0f
            val derivedConfidence = maxOf(providerSignal * .70f, languageSignal * .55f)
            val confidence = when {
                direct -> .90f
                derivedConfidence > 0f -> derivedConfidence
                else -> 0f
            }
            key.takeIf { confidence > 0f }?.let { it to confidence }
        }.toMap()
        val confidence=(profile.metadataConfidence*.35f+(if(a.containsKey("energy")) .3f else 0f)+
            (if(a.containsKey("tempo")) .2f else 0f)+(if(profile.genres.isNotEmpty()) .15f else 0f)).coerceIn(0f,1f)
        return TrackGenome(profile.trackId,identity(profile.artistKey),identity(album),factors,genres,
            profile.languages.mapKeys{normalize(it.key)}.mapValues{it.value*100f},confidence,
            factorConfidence = factorConfidence).let(TrackGenome::normalized)
            .also{GenomeMetrics.built(it.confidence)}
    }

    private fun canonicalFactor(value: String) = when (value.trim().lowercase().replace('_', ' ').replace('-', ' ').replace(" ", "")) {
        "instrumentalness" -> "instrumental"
        "nightdrive" -> "night"
        "latenight" -> "lateNight"
        "discovery" -> "discoveryScore"
        "mainstream" -> "mainstreamIndex"
        "replay" -> "repeatPotential"
        else -> TrackGenome.DIMENSIONS.firstOrNull { it.equals(value.trim(), true) } ?: value.trim()
    }

    fun identity(value:String)=value.trim().lowercase().takeIf(String::isNotBlank)?.hashCode()?.toUInt()?.toString(16).orEmpty()
    private fun normalize(value:String)=value.trim().lowercase().replace('_',' ').replace('-',' ')
}
