package com.example.kilo.recommendation.ranking

import com.example.kilo.recommendation.session.SessionState
import kotlin.math.ceil

object DiversityEngine {
    /** Max two artist appearances in the rolling 15 and two album plays per session. */
    fun select(items:List<RankedGenome>,session:SessionState?,limit:Int,languageShare:Float=85f):List<RankedGenome>{
        val out=mutableListOf<RankedGenome>()
        val albums=mutableMapOf<String,Int>()
        val genres=mutableMapOf<String,Int>();val languages=mutableMapOf<String,Int>()
        val totalSession=(session?.playedCount?:0)+limit
        val genreLocked=session?.mode==com.example.kilo.recommendation.session.SessionMode.GENRE
        val languageLocked=genreLocked&&session?.lockKey?.removePrefix("genre:") in
            setOf("english","hindi","tamil","telugu","kannada","malayalam","punjabi","japanese","korean")
        val maxGenre=if(genreLocked)Int.MAX_VALUE else ceil(totalSession*.85).toInt().coerceAtLeast(1)
        val maxLanguage=if(languageLocked)Int.MAX_VALUE else ceil(totalSession*(languageShare.coerceIn(50f,100f)/100f)).toInt().coerceAtLeast(1)
        val artistWindow=session?.artistHistory?.takeLast(14).orEmpty().toMutableList()
        session?.genreHistory.orEmpty().groupingBy{it}.eachCount().forEach{(k,v)->genres[k]=v}
        session?.albumHistory.orEmpty().groupingBy{it}.eachCount().forEach{(k,v)->albums[k]=v}
        for(item in items){
            if(out.size>=limit)break
            val g=item.genome
            val genre=g.genres.maxByOrNull{it.value}?.key.orEmpty()
            val language=g.languages.maxByOrNull{it.value}?.key.orEmpty()
            if(g.artistKey.isNotBlank()&&artistWindow.count{it==g.artistKey}>=2)continue
            if(g.albumKey.isNotBlank()&&(albums[g.albumKey]?:0)>=2)continue
            if(genre.isNotBlank()&&(genres[genre]?:0)>=maxGenre)continue
            if(language.isNotBlank()&&(languages[language]?:0)>=maxLanguage)continue
            out+=item
            if(g.artistKey.isNotBlank()){
                artistWindow+=g.artistKey
                if(artistWindow.size>14)artistWindow.removeAt(0)
            }
            if(g.albumKey.isNotBlank())albums[g.albumKey]=(albums[g.albumKey]?:0)+1
            if(genre.isNotBlank())genres[genre]=(genres[genre]?:0)+1
            if(language.isNotBlank())languages[language]=(languages[language]?:0)+1
        }
        return out
    }
}
