package com.example.kilo.recommendation.genome

import android.content.Context
import android.util.LruCache
import org.json.JSONObject

/** Bounded memory and local persistence for compact learned Genome X vectors. */
object GenomeDatabase {
    // v3 drops the earlier query-derived genre hints from persisted track genomes.
    private const val PREF="kilo_genome_x_v3";private const val INDEX="ids";private const val CAP=600
    private val memory=object:LruCache<String,TrackGenome>(CAP){}
    fun get(context:Context,id:String):TrackGenome?{
        synchronized(memory){memory.get(id)}?.let{GenomeMetrics.hit();return it}
        val raw=context.applicationContext.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString("g:"+id,null)
            ?:run{GenomeMetrics.miss();return null}
        val decoded=runCatching{
            val j=JSONObject(raw)
            fun map(key:String):Map<String,Float>{
                val obj=j.optJSONObject(key)?:return emptyMap()
                return buildMap{val keys=obj.keys();while(keys.hasNext()){val k=keys.next();put(k,obj.optDouble(k).toFloat())}}
            }
            TrackGenome(id,j.optString("a"),j.optString("b"),map("f"),map("genres"),map("languages"),
                j.optDouble("confidence").toFloat(),j.optLong("updated"),map("factorConfidence")).let(TrackGenome::normalized)
        }.getOrNull()?:run{GenomeMetrics.miss();return null}
        synchronized(memory){memory.put(id,decoded)};GenomeMetrics.hit();return decoded
    }
    fun getAll(context:Context,ids:Collection<String>)=ids.distinct().mapNotNull{id->get(context,id)?.let{ id to it }}.toMap()
    fun all(context:Context):List<TrackGenome>{
        val ids=context.applicationContext.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .getStringSet(INDEX,emptySet()).orEmpty().take(CAP)
        return getAll(context,ids).values.sortedByDescending{it.updatedAtMs}
    }
    fun put(context:Context,items:Collection<TrackGenome>){
        if(items.isEmpty())return
        val prefs=context.applicationContext.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        val ids=prefs.getStringSet(INDEX,emptySet()).orEmpty().toMutableSet();val edit=prefs.edit()
        items.forEach{raw->
            val genome=TrackGenome.normalized(raw);ids+=genome.trackId;synchronized(memory){memory.put(genome.trackId,genome)}
            val factors=JSONObject().apply{genome.factors.forEach{(k,v)->put(k,v.toDouble())}}
            val genres=JSONObject().apply{genome.genres.forEach{(k,v)->put(k,v.toDouble())}}
            val languages=JSONObject().apply{genome.languages.forEach{(k,v)->put(k,v.toDouble())}}
            val factorConfidence=JSONObject().apply{genome.factorConfidence.forEach{(k,v)->put(k,v.toDouble())}}
            edit.putString("g:"+genome.trackId,JSONObject().put("a",genome.artistKey).put("b",genome.albumKey)
                .put("f",factors).put("genres",genres).put("languages",languages).put("factorConfidence",factorConfidence)
                .put("confidence",genome.confidence.toDouble()).put("updated",genome.updatedAtMs).toString())
        }
        val keep=(items.sortedByDescending{it.updatedAtMs}.map{it.trackId}+ids).distinct().take(CAP)
        (ids-keep.toSet()).forEach{edit.remove("g:"+it);synchronized(memory){memory.remove(it)}}
        edit.putStringSet(INDEX,keep.toSet()).apply()
    }
}
