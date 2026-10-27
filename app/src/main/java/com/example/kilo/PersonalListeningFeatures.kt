package com.example.kilo

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.UUID

/** A completed car-listening session awaiting a name from the listener. */
data class DriveRecapPending(
    val id: String,
    val completedAtMs: Long,
    val songs: List<SongItemClean>
)

/**
 * Records only Android Auto sessions. Natural track completions are included; manually
 * skipped tracks are deliberately discarded. The active Media3 player and queue are untouched.
 */
internal object DriveRecapStore {
    private const val TAG = "DriveRecap"
    private const val PENDING_KEY = "drive_recap_pending_v1"
    private const val MIN_PARTIAL_LISTEN_MS = 30_000L

    private var preferences: SharedPreferences? = null
    private val controllers = mutableSetOf<String>()
    private val completedSongs = LinkedHashMap<String, SongItemClean>()
    private var currentSong: SongItemClean? = null
    private var currentListenedMs = 0L
    private var segmentStartedAtElapsedMs: Long? = null

    @Synchronized
    fun init(context: Context) {
        if (preferences == null) {
            preferences = context.applicationContext.getSharedPreferences("vybe_app_settings", Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    fun controllerConnected(controllerId: String, item: MediaItem?, isPlaying: Boolean) {
        val wasDisconnected = controllers.isEmpty()
        controllers.add(controllerId)
        if (wasDisconnected) {
            completedSongs.clear()
            currentSong = item?.toRecapSong()
            currentListenedMs = 0L
            segmentStartedAtElapsedMs = if (isPlaying && currentSong != null) android.os.SystemClock.elapsedRealtime() else null
            Log.i(TAG, "drive session started controller=$controllerId")
        }
    }

    @Synchronized
    fun controllerDisconnected(controllerId: String, isPlaying: Boolean) {
        controllers.remove(controllerId)
        if (controllers.isNotEmpty()) return
        finishCurrentSegment(isPlaying = false)
        includeCurrentIfMeaningfullyHeard()
        publishRecap()
        clearActiveSession()
    }

    @Synchronized
    fun mediaItemTransition(item: MediaItem?, reason: Int, isPlaying: Boolean) {
        if (controllers.isEmpty()) return
        finishCurrentSegment(isPlaying = false)
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            currentSong?.let(::includeSong)
        }
        // Seek, explicit next/previous, queue replacement, and errors do not count the
        // song being left as part of the recap.
        currentSong = item?.toRecapSong()
        currentListenedMs = 0L
        if (isPlaying && currentSong != null) segmentStartedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun playbackChanged(isPlaying: Boolean) {
        if (controllers.isEmpty()) return
        finishCurrentSegment(isPlaying)
    }

    @Synchronized
    fun serviceDestroyed(isPlaying: Boolean) {
        if (controllers.isEmpty()) return
        finishCurrentSegment(isPlaying = false)
        includeCurrentIfMeaningfullyHeard()
        publishRecap()
        clearActiveSession()
        controllers.clear()
    }

    @Synchronized
    fun pending(prefs: SharedPreferences): DriveRecapPending? = readPendingQueue(prefs).firstOrNull()

    @Synchronized
    fun removePending(prefs: SharedPreferences, id: String) {
        val remaining = readPendingQueue(prefs).filterNot { it.id == id }
        writePendingQueue(prefs, remaining)
    }

    private fun finishCurrentSegment(isPlaying: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        segmentStartedAtElapsedMs?.let { started ->
            currentListenedMs += (now - started).coerceAtLeast(0L)
        }
        segmentStartedAtElapsedMs = if (isPlaying && currentSong != null && controllers.isNotEmpty()) now else null
    }

    private fun includeCurrentIfMeaningfullyHeard() {
        if (currentListenedMs >= MIN_PARTIAL_LISTEN_MS) currentSong?.let(::includeSong)
    }

    private fun includeSong(song: SongItemClean) {
        if (song.videoId.isBlank() || song.videoId.startsWith("radio:")) return
        completedSongs.putIfAbsent(song.videoId, song)
    }

    private fun publishRecap() {
        val prefs = preferences ?: return
        val songs = completedSongs.values.toList()
        if (songs.isEmpty()) return
        val newRecap = DriveRecapPending(UUID.randomUUID().toString(), System.currentTimeMillis(), songs)
        val queue = (readPendingQueue(prefs) + newRecap).takeLast(5)
        writePendingQueue(prefs, queue)
        Log.i(TAG, "drive recap ready songs=${songs.size} recap=${newRecap.id}")
    }

    private fun clearActiveSession() {
        completedSongs.clear()
        currentSong = null
        currentListenedMs = 0L
        segmentStartedAtElapsedMs = null
    }

    private fun readPendingQueue(prefs: SharedPreferences): List<DriveRecapPending> = runCatching {
        val root = JSONArray(prefs.getString(PENDING_KEY, "[]"))
        (0 until root.length()).mapNotNull recapLoop@ { index ->
            val recap = root.optJSONObject(index) ?: return@recapLoop null
            val songsArray = recap.optJSONArray("songs") ?: return@recapLoop null
            val songs = (0 until songsArray.length()).mapNotNull songLoop@ { songIndex ->
                val song = songsArray.optJSONObject(songIndex) ?: return@songLoop null
                val id = song.optString("id").trim()
                val videoId = song.optString("videoId").trim()
                if (id.isBlank() || videoId.isBlank()) return@songLoop null
                SongItemClean(
                    id = id,
                    videoId = videoId,
                    title = song.optString("title", "Unknown track"),
                    artist = song.optString("artist", "Unknown artist"),
                    album = song.optString("album", ""),
                    coverUrl = song.optString("coverUrl", ""),
                    durationMs = song.optLong("durationMs", 210_000L)
                )
            }
            if (songs.isEmpty()) null else DriveRecapPending(
                recap.optString("id"), recap.optLong("completedAtMs"), songs
            )
        }
    }.onFailure { Log.w(TAG, "Could not read pending drive recaps", it) }.getOrDefault(emptyList())

    private fun writePendingQueue(prefs: SharedPreferences, recaps: List<DriveRecapPending>) {
        val array = JSONArray()
        recaps.forEach { recap ->
            val songs = JSONArray()
            recap.songs.forEach { song ->
                songs.put(JSONObject().apply {
                    put("id", song.id)
                    put("videoId", song.videoId)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("album", song.album)
                    put("coverUrl", song.coverUrl)
                    put("durationMs", song.durationMs)
                })
            }
            array.put(JSONObject().put("id", recap.id).put("completedAtMs", recap.completedAtMs).put("songs", songs))
        }
        prefs.edit().putString(PENDING_KEY, array.toString()).apply()
    }

    private fun MediaItem.toRecapSong(): SongItemClean? = runCatching {
        if (mediaId.isBlank() || mediaId.startsWith("radio:")) null else toSong()
    }.getOrNull()
}

/** Stores negative feedback against one mood only; global recommendation settings stay intact. */
internal object MoodTrackFeedbackStore {
    private const val KEY = "mood_track_feedback_v1"

    fun hiddenIds(preferences: SharedPreferences, mood: String): Set<String> {
        val moodKey = normalizeMood(mood)
        if (moodKey.isBlank()) return emptySet()
        return runCatching {
            val values = JSONObject(preferences.getString(KEY, "{}") ?: "{}")
                .optJSONArray(moodKey) ?: return@runCatching emptySet()
            (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }.toSet()
        }.getOrDefault(emptySet())
    }

    fun setNotForMood(preferences: SharedPreferences, mood: String, song: SongItemClean) {
        val moodKey = normalizeMood(mood)
        if (moodKey.isBlank()) return
        runCatching {
            val all = JSONObject(preferences.getString(KEY, "{}") ?: "{}")
            val ids = hiddenIds(preferences, mood).toMutableSet()
            song.id.takeIf(String::isNotBlank)?.let(ids::add)
            song.videoId.takeIf(String::isNotBlank)?.let(ids::add)
            val values = JSONArray()
            ids.forEach(values::put)
            all.put(moodKey, values)
            preferences.edit().putString(KEY, all.toString()).apply()
        }.onFailure { Log.w("MoodTrackFeedback", "Could not save mood-specific feedback", it) }
    }

    private fun normalizeMood(mood: String): String = mood.trim().lowercase(java.util.Locale.ROOT).take(80)
}
