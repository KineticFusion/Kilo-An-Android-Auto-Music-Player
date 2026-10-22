@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.kilo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.example.kilo.streaming.*
import com.example.kilo.profile.*
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NPRequest
import org.schabi.newpipe.extractor.downloader.Response as NPResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo as NPStreamInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private class PlayedSongHistoryDb(context: Context) : SQLiteOpenHelper(context, "kilo_played_history.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE played_songs (song_id TEXT PRIMARY KEY NOT NULL, played_at INTEGER NOT NULL)")
        createInteractionsTable(db)
        createDiscoveryTable(db)
        createCatalogStateTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createInteractionsTable(db)
        if (oldVersion < 3) createDiscoveryTable(db)
        if (oldVersion < 4) createCatalogStateTable(db)
    }

    private fun createInteractionsTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS discovery_interactions (event_id INTEGER PRIMARY KEY AUTOINCREMENT, song_id TEXT NOT NULL, artist TEXT NOT NULL, title TEXT NOT NULL, `action` TEXT NOT NULL, value REAL NOT NULL, event_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS discovery_interactions_artist_idx ON discovery_interactions(artist, `action`)")
    }

    private fun createDiscoveryTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS discovery_catalog (song_id TEXT PRIMARY KEY NOT NULL, video_id TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, cover_url TEXT NOT NULL, duration_ms INTEGER NOT NULL, is_explicit INTEGER NOT NULL, first_seen_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, source TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS discovery_catalog_seen_idx ON discovery_catalog(last_seen_at)")
    }

    private fun createCatalogStateTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS catalog_state (state_key TEXT PRIMARY KEY NOT NULL, state_value TEXT NOT NULL)")
    }
}

private data class DiscoveryProfile(val artistWeights: Map<String, Float>, val recentSearches: List<String>)

internal object PlayedSongHistory {
    @Volatile private var database: PlayedSongHistoryDb? = null
    @Volatile private var cache: MutableSet<String>? = null

    fun init(context: Context) {
        if (database == null) synchronized(this) {
            if (database == null) database = PlayedSongHistoryDb(context.applicationContext)
        }
    }

    @Synchronized
    fun ids(): Set<String> {
        cache?.let { return it.toSet() }
        val loaded = linkedSetOf<String>()
        database?.readableDatabase?.rawQuery("SELECT song_id FROM played_songs", null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow("song_id")
            while (cursor.moveToNext()) loaded.add(cursor.getString(idColumn))
        }
        cache = loaded
        return loaded.toSet()
    }

    @Synchronized
    fun record(songId: String) {
        if (songId.isBlank()) return
        val all = cache ?: ids().toMutableSet()
        if (!all.add(songId)) return
        val values = ContentValues().apply {
            put("song_id", songId)
            put("played_at", System.currentTimeMillis())
        }
        database?.writableDatabase?.insertWithOnConflict("played_songs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        cache = all
    }

    @Synchronized
    fun discoveredIds(): Set<String> {
        val ids = linkedSetOf<String>()
        database?.readableDatabase?.rawQuery("SELECT song_id FROM discovery_catalog", null)?.use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getString(0))
        }
        return ids
    }

    @Synchronized
    fun isHomeCatalogComplete(): Boolean = database?.readableDatabase?.rawQuery(
        "SELECT state_value FROM catalog_state WHERE state_key = 'home_complete'", null
    )?.use { it.moveToFirst() && it.getString(0) == "true" } ?: false

    @Synchronized
    fun markHomeCatalogComplete() {
        database?.writableDatabase?.execSQL(
            "INSERT OR REPLACE INTO catalog_state(state_key, state_value) VALUES('home_complete', 'true')"
        )
    }

    /** Songs already found in the user's catalog and not meaningfully played yet. */
    @Synchronized
    fun discoveredButUnplayed(): List<SongItemClean> {
        val songs = mutableListOf<SongItemClean>()
        database?.readableDatabase?.rawQuery(
            "SELECT d.song_id, d.video_id, d.title, d.artist, d.album, d.cover_url, d.duration_ms, d.is_explicit " +
                "FROM discovery_catalog d LEFT JOIN played_songs p ON p.song_id = d.song_id " +
                "WHERE p.song_id IS NULL ORDER BY d.last_seen_at DESC LIMIT 1000",
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                songs += SongItemClean(
                    id = cursor.getString(0), videoId = cursor.getString(1), title = cursor.getString(2),
                    artist = cursor.getString(3), album = cursor.getString(4), coverUrl = cursor.getString(5),
                    durationMs = cursor.getLong(6), isExplicit = cursor.getInt(7) != 0
                )
            }
        }
        return songs
    }

    @Synchronized
    fun markDiscovered(songs: List<SongItemClean>, source: String) {
        val db = database?.writableDatabase ?: return
        val now = System.currentTimeMillis()
        songs.filter { it.videoId.isNotBlank() }.distinctBy { it.id }.forEach { song ->
            val values = ContentValues().apply {
                put("song_id", song.id)
                put("video_id", song.videoId)
                put("title", song.title)
                put("artist", song.artist)
                put("album", song.album)
                put("cover_url", song.coverUrl)
                put("duration_ms", song.durationMs)
                put("is_explicit", if (song.isExplicit) 1 else 0)
                put("first_seen_at", now)
                put("last_seen_at", now)
                put("source", source)
            }
            db.insertWithOnConflict("discovery_catalog", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            val updated = ContentValues().apply {
                put("video_id", song.videoId)
                put("title", song.title)
                put("artist", song.artist)
                put("album", song.album)
                put("cover_url", song.coverUrl)
                put("duration_ms", song.durationMs)
                put("is_explicit", if (song.isExplicit) 1 else 0)
                put("last_seen_at", now)
                put("source", source)
            }
            db.update("discovery_catalog", updated, "song_id = ?", arrayOf(song.id))
        }
    }

    fun recordSignal(song: SongItemClean, action: String, value: Float = 0f) {
        val values = ContentValues().apply {
            put("song_id", song.id)
            put("artist", song.artist)
            put("title", song.title)
            put("action", action)
            put("value", value)
            put("event_at", System.currentTimeMillis())
        }
        database?.writableDatabase?.insert("discovery_interactions", null, values)
    }

    fun recordSearch(query: String) {
        val clean = query.trim().take(180)
        if (clean.isBlank()) return
        recordSignal(SongItemClean(id = "search:${System.currentTimeMillis()}", title = clean, artist = "", album = "", coverUrl = ""), "search")
    }

    private fun profile(): DiscoveryProfile {
        val weights = linkedMapOf<String, Float>()
        database?.readableDatabase?.rawQuery(
            "SELECT artist, SUM(CASE `action` WHEN 'like' THEN 5.0 WHEN 'unlike' THEN -5.0 WHEN 'complete' THEN 3.0 WHEN 'replay' THEN 2.5 WHEN 'listen' THEN MIN(value / 30000.0, 2.0) WHEN 'skip' THEN -4.0 ELSE 0.0 END) AS score FROM discovery_interactions WHERE artist != '' GROUP BY artist ORDER BY score DESC LIMIT 40",
            null
        )?.use { cursor ->
            val artistCol = cursor.getColumnIndexOrThrow("artist")
            val scoreCol = cursor.getColumnIndexOrThrow("score")
            while (cursor.moveToNext()) weights[cursor.getString(artistCol)] = cursor.getFloat(scoreCol)
        }
        val searches = mutableListOf<String>()
        database?.readableDatabase?.rawQuery(
            "SELECT title FROM discovery_interactions WHERE `action` = 'search' ORDER BY event_id DESC LIMIT 20", null
        )?.use { cursor -> while (cursor.moveToNext()) searches.add(cursor.getString(0)) }
        return DiscoveryProfile(weights, searches.distinct())
    }

    fun recommendationArtists(): List<String> = profile().artistWeights.entries
        .filter { it.value > 0f }.sortedByDescending { it.value }.map { it.key }

    @Synchronized
    fun clear() {
        database?.writableDatabase?.delete("played_songs", null, null)
        database?.writableDatabase?.delete("discovery_interactions", null, null)
        database?.writableDatabase?.delete("discovery_catalog", null, null)
        database?.writableDatabase?.delete("catalog_state", null, null)
        cache = mutableSetOf()
    }

    @Synchronized
    fun resetRecommendationSignals() {
        database?.writableDatabase?.delete("discovery_interactions", null, null)
        database?.writableDatabase?.delete("discovery_catalog", null, null)
        database?.writableDatabase?.delete("catalog_state", null, null)
    }
}
// ───────────────────────── SHARED DATA MODELS ─────────────────────────

data class SongItemClean(
    val id: String,
    val videoId: String = "",
    val playlistId: String = "",
    val albumBrowseId: String = "",
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String,
    val audioUrl: String = "",
    val themeColor: Color = Color(0xFFFF7043),
    val durationMs: Long = 215000L,
    val codecInfo: String = "AAC • 44.1 kHz • 256 kbps • Stereo",
    val isDownloaded: Boolean = false,
    val isExplicit: Boolean = false
)

data class HomeShelf(val title: String, val songs: List<SongItemClean>, val isList: Boolean)
data class ArtistItem(val id: String, val name: String, val subtitle: String, val coverUrl: String)

private data class RegionalArtistPicks(val label: String, val artists: List<String>)

private val GLOBAL_ARTIST_PICKS = listOf(
    "Taylor Swift", "The Weeknd", "Beyoncé", "Drake", "Ed Sheeran", "Billie Eilish",
    "Bad Bunny", "BTS", "Shakira", "Adele", "Bruno Mars", "Coldplay"
)

private fun regionalArtistPicks(countryCode: String): RegionalArtistPicks = when (countryCode.uppercase(Locale.ROOT)) {
    "IN", "BD", "NP", "LK", "PK" -> RegionalArtistPicks("South Asia", listOf(
        "Arijit Singh", "A. R. Rahman", "Shreya Ghoshal", "Diljit Dosanjh", "Anirudh Ravichander",
        "Sid Sriram", "Atif Aslam", "Ritviz", "Jasleen Royal", "Sanam Puri", "Pritam", "Karan Aujla"
    ))
    "US", "CA" -> RegionalArtistPicks("North America", listOf(
        "SZA", "Kendrick Lamar", "Post Malone", "Ariana Grande", "Justin Bieber", "Tyler, The Creator",
        "Olivia Rodrigo", "Travis Scott", "Morgan Wallen", "The Marías", "Peso Pluma", "Tate McRae"
    ))
    "GB", "IE" -> RegionalArtistPicks("United Kingdom & Ireland", listOf(
        "Harry Styles", "Dua Lipa", "RAYE", "Sam Fender", "Central Cee", "Little Simz",
        "Lewis Capaldi", "Stormzy", "The 1975", "Fontaines D.C.", "Hozier", "Fred again.."
    ))
    "BR", "PT" -> RegionalArtistPicks("Brazil & Portugal", listOf(
        "Anitta", "Ludmilla", "Jorge & Mateus", "Luísa Sonza", "Marília Mendonça", "IZA",
        "Pedro Sampaio", "Caetano Veloso", "Baco Exu do Blues", "Pabllo Vittar", "Dilsinho", "Veigh"
    ))
    "MX", "AR", "CL", "CO", "PE", "ES" -> RegionalArtistPicks("Latin America & Spain", listOf(
        "Karol G", "Feid", "Rauw Alejandro", "J Balvin", "Peso Pluma", "Natalia Lafourcade",
        "Rosalía", "Bizarrap", "Shakira", "Manuel Turizo", "Morat", "Mon Laferte"
    ))
    "NG", "GH", "ZA", "KE", "TZ", "UG" -> RegionalArtistPicks("Africa", listOf(
        "Burna Boy", "Wizkid", "Tems", "Davido", "Ayra Starr", "Rema",
        "Asake", "Black Coffee", "Tyla", "Sauti Sol", "Diamond Platnumz", "Sarkodie"
    ))
    "KR" -> RegionalArtistPicks("South Korea", listOf(
        "BLACKPINK", "NewJeans", "Stray Kids", "SEVENTEEN", "aespa", "IU",
        "LE SSERAFIM", "TWICE", "BTS", "Jung Kook", "IVE", "ENHYPEN"
    ))
    "JP" -> RegionalArtistPicks("Japan", listOf(
        "YOASOBI", "Ado", "Fujii Kaze", "Kenshi Yonezu", "Official HIGE DANdism", "King Gnu",
        "Mrs. GREEN APPLE", "Hikaru Utada", "Vaundy", "LiSA", "Creepy Nuts", "Aimer"
    ))
    "AU", "NZ" -> RegionalArtistPicks("Australia & New Zealand", listOf(
        "Tame Impala", "Sia", "The Kid LAROI", "Troye Sivan", "Kylie Minogue", "Lorde",
        "Dean Lewis", "Kita Alexander", "G Flip", "Dom Dolla", "RÜFÜS DU SOL", "BENEE"
    ))
    else -> RegionalArtistPicks("Popular across Asia", listOf(
        "Arijit Singh", "A. R. Rahman", "BTS", "BLACKPINK", "YOASOBI", "Anirudh Ravichander",
        "Atif Aslam", "Ritviz", "NewJeans", "Jasleen Royal", "Ado", "Diljit Dosanjh"
    ))
}

private fun discoveryLabelKey(value: String): String = value.lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

fun moodDiscoveryQuery(mood: String, genre: String? = null): String {
    val moodTerms = when (mood.trim().lowercase(Locale.ROOT)) {
        "focus" -> "instrumental focus music background mix"
        "study" -> "study piano instrumental playlist music"
        "coding" -> "coding electronic instrumental music mix"
        "workout" -> "workout high energy music mix"
        "night drive" -> "night driving synthwave electronic soundtrack mix"
        "road trip" -> "road trip driving pop rock playlist mix"
        "commute" -> "upbeat commute pop music mix"
        "gaming" -> "gaming electronic phonk instrumental mix"
        "relax" -> "relaxing ambient acoustic chill music mix"
        "sleep" -> "sleep ambient piano calming instrumental mix"
        "party" -> "party dance pop playlist mix"
        "high energy" -> "high energy dance edm workout mix"
        "happy" -> "happy upbeat feel good pop songs mix"
        "sad" -> "melancholic acoustic emotional songs mix"
        "romantic" -> "romantic r&b soul love songs mix"
        "nostalgic" -> "nostalgic classic 80s 90s pop hits mix"
        else -> "${mood.trim()} music playlist mix"
    }
    val cleanGenre = genre?.trim().orEmpty()
    return listOf(moodTerms, cleanGenre.takeIf(String::isNotBlank)).filterNotNull().joinToString(" ")
}

fun genreDiscoveryQuery(genre: String): String = "${genre.trim()} songs playlist mix"

fun filterMoodTitleMatches(songs: List<SongItemClean>, mood: String): List<SongItemClean> {
    val moodKey = discoveryLabelKey(mood)
    if (moodKey.isBlank()) return songs
    return songs.filterNot { discoveryLabelKey(it.title) == moodKey }
}

fun filterGenreTitleMatches(songs: List<SongItemClean>, genre: String): List<SongItemClean> {
    val genreKey = discoveryLabelKey(genre)
    if (genreKey.isBlank()) return songs
    return songs.filterNot { discoveryLabelKey(it.title) == genreKey }
}
data class VideoStreamInfo(
    val videoUrl: String,
    val audioUrl: String?,
    val userAgent: String,
    val dashManifestUrl: String? = null
)
data class DownloadTaskState(val song: SongItemClean, val progress: Float? = null)

data class UserProfileAccount(
    val id: String,
    val name: String,
    val profileImage: String,
    val cookie: String,
    val favoriteGenre: String
)

data class SavedPlaylist(val id: String, val name: String, val songs: List<SongItemClean>)

data class PlayerStylePreset(
    val id: String,
    val name: String,
    val accent: Color,
    val background: Color,
    val cornerRadius: Int,
    val glow: Float,
    val surfaceOpacity: Float,
    val controlShape: Int,
    val backgroundStyle: Int,
    val detailStyle: Int,
    val layoutStyle: Int
)

private val PLAYER_STYLE_FAMILIES = listOf(
    "Sunset" to Color(0xFFFF7A59), "Aurora" to Color(0xFF38EF7D), "Amethyst" to Color(0xFFB46CFF),
    "Ocean" to Color(0xFF40C8FF), "Rose" to Color(0xFFFF5F9E), "Gold" to Color(0xFFFFC857),
    "Indigo" to Color(0xFF7185FF), "Teal" to Color(0xFF40E0D0), "Coral" to Color(0xFFFF6B6B),
    "Cherry" to Color(0xFFFF3D71), "Flame" to Color(0xFFFF4D00), "Tangerine" to Color(0xFFFF9F1C),
    "Lemon" to Color(0xFFFFE66D), "Lime" to Color(0xFFB7F34D), "Mint" to Color(0xFF71E6B3),
    "Jade" to Color(0xFF00B894), "Aqua" to Color(0xFF00E5FF), "Glacier" to Color(0xFFA5F3FC),
    "Sky" to Color(0xFF7CCBFF), "Cobalt" to Color(0xFF2864FF), "Sapphire" to Color(0xFF005BFF),
    "Violet" to Color(0xFF8C52FF), "Orchid" to Color(0xFFDA70D6), "Magenta" to Color(0xFFFF2EA6),
    "Fuchsia" to Color(0xFFE600FF), "Blush" to Color(0xFFF7A8B8), "Sand" to Color(0xFFD8B48A),
    "Copper" to Color(0xFFC87941), "Bronze" to Color(0xFFCD7F32), "Silver" to Color(0xFFC7D0E0),
    "Arctic" to Color(0xFFE3F6FF), "Neon" to Color(0xFFC7FF00), "Midnight" to Color(0xFF6677CC)
)

private data class PlayerStyleMood(val name: String, val background: Color, val cornerRadius: Int, val glow: Float, val surfaceOpacity: Float)

private val PLAYER_STYLE_MOODS = listOf(
    PlayerStyleMood("Velvet", Color(0xFF1B1326), 30, 0.24f, 0.92f),
    PlayerStyleMood("Obsidian", Color(0xFF0A0C12), 24, 0.14f, 0.98f),
    PlayerStyleMood("Prism", Color(0xFF1E1936), 36, 0.30f, 0.86f),
    PlayerStyleMood("Minimal", Color(0xFF14151A), 16, 0.08f, 0.96f)
)

val PLAYER_STYLE_PRESETS = PLAYER_STYLE_FAMILIES.flatMapIndexed { familyIndex, (family, accent) ->
    PLAYER_STYLE_MOODS.mapIndexed { moodIndex, mood ->
        val combination = familyIndex * PLAYER_STYLE_MOODS.size + moodIndex
        PlayerStylePreset(
            "style_${familyIndex}_$moodIndex", "$family ${mood.name}", accent, mood.background,
            mood.cornerRadius, mood.glow, mood.surfaceOpacity,
            controlShape = (combination / 2) % 4,
            backgroundStyle = (combination / 3) % 4,
            detailStyle = (combination / 5) % 6,
            layoutStyle = combination % 6
        )
    }
}

private fun playerArtworkShape(style: PlayerStylePreset): androidx.compose.ui.graphics.Shape = RoundedCornerShape(style.cornerRadius.dp)

private fun playerControlShape(style: PlayerStylePreset): androidx.compose.ui.graphics.Shape = when (style.controlShape) {
    1 -> CircleShape
    2 -> RoundedCornerShape(14.dp)
    3 -> RoundedCornerShape(50)
    else -> RoundedCornerShape((style.cornerRadius - 4).coerceAtLeast(10).dp)
}

enum class SkinTheme(val title: String, val highlight: Color, val description: String, val layoutFamily: Int) {
    SUNSET_GLASS("Liquid Glass", Color(0xFFB783FF), "Frosted floating panels and soft ambient color", 0),
    SPOTIFY("Spotify Flow", Color(0xFF1ED760), "Quick grids, dense playlists and a compact player", 1),
    YT_MUSIC("YouTube Music", Color(0xFFFF3355), "Mood shelves, bold artwork and a discovery feed", 2),
    JIOSAAVN("Apple Music", Color(0xFFFF567A), "Editorial Listen Now with oversized album art", 3),
    PRISM_GLASS("Prism Glass", Color(0xFF48D9FF), "Cool glass surfaces with layered artwork", 0),
    FROSTED_NIGHT("Frosted Night", Color(0xFFFF7AC8), "Soft blur, floating controls and calm spacing", 0),
    DEEZER_FLOW("Deezer Flow", Color(0xFF9B6BFF), "Colorful discovery tiles and a flowing mix", 1),
    GAANA_BEATS("Gaana Beats", Color(0xFFFF6848), "Warm compact cards and playlist-first browsing", 1),
    POCKET_MIX("Pocket Mix", Color(0xFFFFB938), "A bright quick-pick grid for fast browsing", 1),
    JIOSAAVN_WAVE("JioSaavn", Color(0xFF28C9B7), "Portrait artwork and language-inspired shelves", 2),
    YANDEX_WAVE("Yandex Wave", Color(0xFFFF6B4A), "A vertical flow of moods and recent tracks", 2),
    NIGHT_DRIVE("Night Drive", Color(0xFF58B8FF), "Wide night-toned artwork and seamless shelves", 2),
    QOBUZ_EDITORIAL("Qobuz Hi-Fi", Color(0xFF58C5D8), "Album-led editorial pages and clean typography", 3),
    AMAZON_EDITORIAL("Amazon Music", Color(0xFF39C9D6), "Personalized sections with clear library access", 3),
    SOUNDCLOUD_WAVE("SoundCloud", Color(0xFFFF7A30), "Waveform accents and a creator-style track feed", 4)
}

private fun primaryCardShape(skin: SkinTheme): androidx.compose.ui.graphics.Shape =
    RoundedCornerShape(listOf(30, 10, 18, 28, 40, 22, 12, 34, 16, 26, 38, 20, 14, 32, 24)[skin.ordinal].dp)

private fun primaryBackdrop(skin: SkinTheme): Brush = when (skin.layoutFamily) {
    0 -> Brush.radialGradient(listOf(skin.highlight.copy(alpha = 0.25f), Color(0xFF161322), Color(0xFF08090D)))
    1 -> Brush.verticalGradient(listOf(Color(0xFF101613), Color(0xFF080A09), Color(0xFF050605)))
    2 -> Brush.verticalGradient(listOf(Color(0xFF291016), Color(0xFF120B10), Color(0xFF08090D)))
    3 -> Brush.verticalGradient(listOf(Color(0xFF2A111C), Color(0xFF171018), Color(0xFF09090D)))
    else -> Brush.verticalGradient(listOf(Color(0xFF1B1015), Color(0xFF101117), Color(0xFF07090D)))
}

private fun primaryPanelColor(skin: SkinTheme): Color = when (skin.layoutFamily) {
    0 -> Color.White.copy(alpha = 0.075f)
    1 -> Color(0xFF202923)
    2 -> Color(0xFF21151A)
    3 -> Color(0xFF24151B)
    else -> Color(0xFF17191F)
}

enum class ScreenNav {
    HOME, EXPLORE, LIBRARY, SEARCH, OFFLINE, RADIO, SETTINGS, APP_APPEARANCE, ANDROID_AUTO_SETTINGS,
    PLAYER_STYLES, EQUALIZER, V2_ROADMAP, ARTIST_DETAIL
}

enum class OverlayTab {
    NONE, QUEUE, LYRICS, CODEC_INFO
}

private val AUTO_LAYOUT_PROFILES = listOf(
    "Balanced" to "A little of everything, starting with your current listening.",
    "Quick picks first" to "Put personal recommendations at the top.",
    "Recently played first" to "Jump back into music you played lately.",
    "Trending first" to "Lead with popular and newly discovered music.",
    "Library first" to "Put liked songs and playlists before discovery."
)
val AUTO_LAYOUT_MODES = AUTO_LAYOUT_PROFILES.flatMapIndexed { profileIndex, profile ->
    (2..6).map { shelfCount -> "${profileIndex + 1}|$shelfCount|${profile.first}" }
}
private val LEGACY_AUTO_LAYOUT_MODES = listOf(
    "01 Classic Browse", "02 Album Focus", "03 Minimal Home", "04 Split Sections", "05 Queue First",
    "06 Search Focus", "07 Browse Home", "08 Category Groups", "09 Horizontal Shelves", "10 Artist Focus",
    "11 Playlist Focus", "12 Album List", "13 Discovery Carousel", "14 Recent First", "15 Large Artwork",
    "16 Quick Picks + Library", "17 Compact Browse", "18 Clean List", "19 Trending First", "20 Mood Shelves",
    "21 Quick Actions", "22 Library First", "23 Artwork First", "24 Audio Controls", "25 Recommendations First"
)
private fun normalizeAutoLayoutMode(value: String?): String {
    val currentIndex = AUTO_LAYOUT_MODES.indexOf(value)
    if (currentIndex >= 0) return AUTO_LAYOUT_MODES[currentIndex]
    val oldIndex = LEGACY_AUTO_LAYOUT_MODES.indexOf(value)
    return AUTO_LAYOUT_MODES.getOrElse(oldIndex.coerceAtLeast(0)) { AUTO_LAYOUT_MODES.first() }
}

val AUTO_MENU_TABS = listOf(
    "Quick picks",
    "Curated for you",
    "Something new to listen",
    "Trending community playlists",
    "Offline downloads"
)

data class UiCustomizationOption(
    val key: String,
    val title: String,
    val description: String,
    val group: String,
    val enabledByDefault: Boolean = true
)

val UI_CUSTOMIZATION_OPTIONS = listOf(
    UiCustomizationOption("glassPanels", "Translucent cards", "Soft frosted card surfaces", "LOOK & FEEL"),
    UiCustomizationOption("gradientBackdrop", "Dynamic canvas", "Blend atmospheric gradients in the background", "LOOK & FEEL"),
    UiCustomizationOption("albumColorTint", "Artwork color sync", "Adapt player accents to the active cover art", "LOOK & FEEL", true),
    UiCustomizationOption("compactRows", "Compact track rows", "Fit more music per screen", "LOOK & FEEL", false),
    UiCustomizationOption("largerArtwork", "Large thumbnails", "Boost cover art visibility in lists", "LOOK & FEEL", false),
    UiCustomizationOption("showCoverArt", "Show cover art", "Display album covers in track lists", "LOOK & FEEL"),
    UiCustomizationOption("roundedCards", "Soft corners", "Use smooth rounded corners on covers", "LOOK & FEEL"),
    UiCustomizationOption("showArtistNames", "Show artist names", "Display artist subtitles", "LOOK & FEEL"),
    UiCustomizationOption("showDurations", "Show durations", "Show song length next to artist", "LOOK & FEEL"),
    UiCustomizationOption("showAlbumNames", "Show album names", "Include album metadata in rows", "LOOK & FEEL", false),
    UiCustomizationOption("showExplicitBadge", "Explicit badges", "Show E tag on explicit tracks", "LOOK & FEEL"),
    UiCustomizationOption("showCodecLabels", "Audio stream badge", "Display live bitrate and codec info", "LOOK & FEEL"),
    UiCustomizationOption("ambientArtwork", "Ambient player glow", "Softly illuminate full-screen player", "LOOK & FEEL"),
    UiCustomizationOption("smoothMotion", "Fluid transitions", "Smoothly animate screen changes", "LOOK & FEEL"),
    UiCustomizationOption("reduceMotion", "Reduce motion", "Use instant snaps instead of slides", "LOOK & FEEL", false),
    UiCustomizationOption("showMiniPlayer", "Floating dock player", "Keep quick playback dock above navigation", "PLAYER"),
    UiCustomizationOption("compactPlayer", "Slim dock player", "Reduce mini player height", "PLAYER", false),
    UiCustomizationOption("largeTransportControls", "Oversized controls", "Easy-tap car-friendly play and skip buttons", "PLAYER"),
    UiCustomizationOption("showWaveform", "Tall seek bar", "Easier scrubbing on the timeline", "PLAYER", false),
    UiCustomizationOption("showQueueIndicator", "Queue shortcut", "Quickly open upcoming tracks", "PLAYER"),
    UiCustomizationOption("autoPlayNext", "Continuous playback", "Automatically advance through queue", "PLAYER"),
    UiCustomizationOption("showGreeting", "Personal header", "Show your name and status on Home", "HOME & LIBRARY"),
    UiCustomizationOption("showQuickPicks", "Quick picks carousel", "Show instant-play hero cards", "HOME & LIBRARY"),
    UiCustomizationOption("showCuratedShelves", "Curated shelves", "Show personalized discovery rows", "HOME & LIBRARY"),
    UiCustomizationOption("compactLibrary", "Compact library", "Tighter spacing in saved songs", "HOME & LIBRARY", false),
    UiCustomizationOption("highContrast", "High contrast text", "Boost subtitle legibility", "ACCESSIBILITY", false),
    UiCustomizationOption("largeText", "Larger typography", "Increase title and label sizes", "ACCESSIBILITY", false)
)

val LocalUiCustomizations = compositionLocalOf<Map<String, Boolean>> { emptyMap() }
val LocalPrimarySkin = compositionLocalOf { SkinTheme.SUNSET_GLASS }

val INITIAL_TRACKS = listOf(
    SongItemClean(
        id = "RLzC55ai0eo",
        videoId = "RLzC55ai0eo",
        title = "Heeriye (feat. Arijit Singh)",
        artist = "Jasleen Royal, Arijit Singh",
        album = "Heeriye",
        coverUrl = "https://i.ytimg.com/vi/RLzC55ai0eo/maxresdefault.jpg",
        themeColor = Color(0xFFE91E63),
        durationMs = 199000L
    ),
    SongItemClean(
        id = "W6_Uv49w3w0",
        videoId = "W6_Uv49w3w0",
        title = "Assumptions",
        artist = "Sam Gellaitry",
        album = "Assumptions",
        coverUrl = "https://i.ytimg.com/vi/W6_Uv49w3w0/maxresdefault.jpg",
        themeColor = Color(0xFFDA70D6),
        durationMs = 198000L
    )
)

data class AppStateModel(
    val isSetupComplete: Boolean = false,
    val setupStep: Int = 0,
    val displayName: String = "Arnav",
    val isLoggedIn: Boolean = false,
    val userAuthCookie: String = "",
    val showWebLoginDialog: Boolean = false,
    val showAccountManagerDialog: Boolean = false,
    val accountsList: List<UserProfileAccount> = listOf(
        UserProfileAccount("1", "Main Profile", "https://picsum.photos/seed/user1/200", "", "Hi-Fi & Pop")
    ),
    val activeAccountId: String = "1",
    val selectedMood: String = "Discover",
    val listenerProfile: ListenerProfile = ListenerProfile(),
    val showPersonalizationWizard: Boolean = false,
    val profileArtistSuggestions: List<ArtistItem> = emptyList(),
    val isFullScreenPlayerOpen: Boolean = false,
    val playerOverlayTab: OverlayTab = OverlayTab.NONE,
    val downloadDirectoryUri: String? = null,
    val uiCustomizations: Map<String, Boolean> = UI_CUSTOMIZATION_OPTIONS.associate { it.key to it.enabledByDefault },
    val selectedPlayerStyleId: String = PLAYER_STYLE_PRESETS.first().id,
    val savedPlaylists: List<SavedPlaylist> = emptyList(),
    val notRecommendedIds: Set<String> = emptySet(),
    val selectedAutoMenus: List<String> = listOf(
        "Quick picks",
        "Curated for you",
        "Something new to listen",
        "Trending community playlists"
    ),
    val activeHomeTab: String = "All",
    val wifiQuality: String = "Lossless",
    val mobileQuality: String = "Lossless",
    val losslessSourceUrl: String = "",
    val losslessSourceEnabled: Boolean = false,
    val losslessSourceHasToken: Boolean = false,
    val losslessSourceStatus: String = "Optional: connect a compatible FLAC source",
    val downloadWifiOnly: Boolean = true,
    val dolbyAtmosEnabled: Boolean = false,
    val activeSkin: SkinTheme = SkinTheme.SUNSET_GLASS,
    val quickPicks: List<SongItemClean> = INITIAL_TRACKS,
    val curatedForYou: List<SongItemClean> = INITIAL_TRACKS,
    val somethingNew: List<SongItemClean> = INITIAL_TRACKS,
    val trendingPlaylists: List<SongItemClean> = INITIAL_TRACKS,
    val playlist: List<SongItemClean> = INITIAL_TRACKS,
    val downloadedSongs: List<SongItemClean> = emptyList(),
    val downloadsInProgress: Map<String, DownloadTaskState> = emptyMap(),
    val isScanningDownloads: Boolean = false,
    val homeShelves: List<HomeShelf> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<SongItemClean> = emptyList(),
    val artistSearchResults: List<ArtistItem> = emptyList(),
    val activeArtist: ArtistItem? = null,
    val artistAlbums: List<SongItemClean> = emptyList(),
    val artistSongs: List<SongItemClean> = emptyList(),
    val selectedArtistAlbumTitle: String? = null,
    val musicRegionCode: String = "AUTO",
    val communityPlaylistResults: List<SongItemClean> = emptyList(),
    val libraryTab: Int = 0,
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isLoadingStream: Boolean = false,
    val videoMode: Boolean = false,
    val autoMixEnabled: Boolean = false,
    val autoConnectCar: Boolean = true,
    val autoPlayOnCarConnect: Boolean = false,
    val pauseForCalls: Boolean = true,
    val autoLayoutMode: String = AUTO_LAYOUT_MODES.first(),
    val filterMatureContent: Boolean = true,
    val progress: Float = 0f,
    val currentPosMs: Long = 0L,
    val currentDurationMs: Long = 215000L,
    val favoriteIds: Set<String> = emptySet(),
    val radioFavorites: List<RadioStation> = emptyList(),
    val radioRecentStations: List<RadioStation> = emptyList(),
    val customRadioStations: List<RadioStation> = emptyList(),
    val lastRadioStation: RadioStation? = null,
    val radioSleepTimerMinutes: Int? = null,
    val isRadioRecording: Boolean = false,
    val isSearching: Boolean = false,
    val equalizerSnapshot: EqualizerSnapshot = EqualizerSnapshot(),
    val isCheckingForUpdates: Boolean = false,
    val updateCheckMessage: String = "Not checked yet",
    val latestReleaseUrl: String = "",
    val latestApkUrl: String = "",
    val latestReleaseTitle: String = "",
    val updateAvailable: Boolean = false,
    val currentScreen: ScreenNav = ScreenNav.HOME
) {
    val activeSong: SongItemClean
        get() = playlist.getOrNull(currentIndex)
            ?: quickPicks.firstOrNull()
            ?: INITIAL_TRACKS.first()
}

// ───────────────────────── NEWPIPE DOWNLOADER ─────────────────────────

class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: NPRequest): NPResponse {
        val url = request.url()
        val dataToSend = request.dataToSend()
        val body = dataToSend?.toRequestBody(null, 0, dataToSend.size)

        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0")

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        val response = client.newCall(builder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }
        val text = response.body?.string()
        return NPResponse(response.code, response.message, response.headers.toMultimap(), text, response.request.url.toString())
    }
}

// ─────────────────────────SHARED YT REPO (USED BY APP + ANDROID AUTO) ─────────────────────────

object dYtRepo {
    private const val TAG = "YtRepo"
    private const val ORIGIN = "https://music.youtube.com"
    private const val WEB_REMIX_VERSION = "1.20250310.01.00"
    private const val SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    val paletteColors = listOf(
        Color(0xFFFF7043), Color(0xFFAB47BC), Color(0xFF26A69A), Color(0xFF42A5F5), Color(0xFFFFCA28)
    )

    @Volatile
    var userAuthCookie: String = ""

    fun refreshCookieFromWebView() {
        runCatching {
            val c = CookieManager.getInstance().getCookie(ORIGIN).orEmpty()
            if (c.isNotBlank()) userAuthCookie = c
        }
    }

    private fun buildSapisidAuthorizationHeader(cookies: String): String? {
        val sapisid = Regex("(?:^|;\\s*)SAPISID=([^;]+)").find(cookies)?.groupValues?.get(1)
            ?: Regex("__Secure-3PAPISID=([^;]+)").find(cookies)?.groupValues?.get(1)
            ?: return null
        val ts = System.currentTimeMillis() / 1000
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest("$ts $sapisid $ORIGIN".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${ts}_$sha1"
    }

    fun ytMusicPost(endpoint: String, body: JSONObject, extraQuery: String = ""): JSONObject? {
        return try {
            val conn = (URL("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false$extraQuery")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Origin", ORIGIN)
                setRequestProperty("Referer", "$ORIGIN/")
                setRequestProperty("X-Origin", ORIGIN)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("User-Agent", WEB_UA)
                val cookie = userAuthCookie
                if (cookie.isNotEmpty()) {
                    setRequestProperty("Cookie", cookie)
                    setRequestProperty("X-Goog-AuthUser", "0")
                    buildSapisidAuthorizationHeader(cookie)?.let { setRequestProperty("Authorization", it) }
                } else {
                    setRequestProperty("Cookie", "SOCS=CAI")
                }
            }
            body.put("context", JSONObject().put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", WEB_REMIX_VERSION)
                put("hl", "en")
                put("gl", "IN")
            }))
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) null else JSONObject(text)
        } catch (e: Exception) {
            Log.e(TAG, "$endpoint failed", e)
            null
        }
    }

    fun searchSongs(query: String): List<SongItemClean> {
        var json = ytMusicPost("search", JSONObject().put("query", query).put("params", SONGS_FILTER))
        var songs = json?.let { parseSongs(it) }.orEmpty()
        if (songs.isEmpty()) {
            json = ytMusicPost("search", JSONObject().put("query", query))
            songs = json?.let { parseSongs(it) }.orEmpty()
        }
        return songs
    }

    fun fetchHome(): List<HomeShelf> {
        val shelves = mutableListOf<HomeShelf>()
        var json = ytMusicPost("browse", JSONObject().put("browseId", "FEmusic_home"))
        var pages = 0
        while (json != null && pages < 2) {
            shelves += parseShelves(json)
            val tokens = mutableListOf<JSONObject>()
            collect(json, "nextContinuationData", tokens)
            val token = tokens.firstOrNull()?.optString("continuation").orEmpty()
            if (token.isEmpty()) break
            val enc = URLEncoder.encode(token, "UTF-8")
            json = ytMusicPost("browse", JSONObject(), "&ctoken=$enc&continuation=$enc&type=next")
            pages++
        }
        return shelves
    }

    fun fetchPlaylistTracks(playlistId: String): List<SongItemClean> {
        val json = ytMusicPost(
            "next",
            JSONObject().put("playlistId", playlistId)
                .put("enablePersistentPlaylistPanel", true)
                .put("isAudioOnly", true)
        ) ?: return emptyList()
        val nodes = mutableListOf<JSONObject>()
        collect(json, "playlistPanelVideoRenderer", nodes)
        return nodes.mapIndexedNotNull { idx, r ->
            val vid = r.optString("videoId")
            if (vid.isEmpty()) return@mapIndexedNotNull null
            val title = runsText(r.optJSONObject("title")).firstOrNull() ?: return@mapIndexedNotNull null
            val artist = runsText(r.optJSONObject("longBylineText")).firstOrNull() ?: "Unknown"
            val dur = parseDuration(runsText(r.optJSONObject("lengthText")).firstOrNull().orEmpty())
            SongItemClean(
                id = vid, videoId = vid, title = title, artist = artist, album = "YouTube Music",
                coverUrl = bestThumb(r.optJSONObject("thumbnail")),
                themeColor = paletteColors[idx % paletteColors.size],
                durationMs = dur
            )
        }
    }

    fun fetchStream(videoId: String): StreamInfo? {
        return FastStream.get(videoId, fallback = { id: String -> fetchStreamNewPipe(id) })
    }

    private fun userAgentFor(url: String): String {
        val client = Regex("[?&]c=([A-Z_0-9]+)").find(url)?.groupValues?.get(1).orEmpty()
        return when (client) {
            "IOS" -> "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
            "ANDROID_VR" -> "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
            "ANDROID" -> "com.google.android.youtube/19.28.35 (Linux; U; Android 14; GB) gzip"
            else -> "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
        }
    }

    fun fetchStreamNewPipe(videoId: String): StreamInfo? {
        return try {
            val info = NPStreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val compatibleStreams = info.audioStreams.orEmpty()
                .filter { (it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod == DeliveryMethod.DASH) && it.content.isNotEmpty() }
            val best = compatibleStreams
                .filter { it.format?.name?.contains("m4a", ignoreCase = true) == true || it.format?.name?.contains("opus", ignoreCase = true) == true }
                .maxByOrNull { it.averageBitrate }
                ?: compatibleStreams.maxByOrNull { it.averageBitrate }
                ?: return null
            val kbps = if (best.averageBitrate > 0) best.averageBitrate / 1000 else 128
            val fmt = best.format?.getName().orEmpty().ifEmpty { "AAC" }
            StreamInfo(
                best.content, "$fmt • $kbps kbps • Stereo", userAgentFor(best.content),
                bitrateKbps = kbps
            )
        } catch (e: Exception) {
            Log.e(TAG, "NewPipe extraction failed for $videoId", e)
            null
        }
    }

    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (k == key && v is JSONObject) out.add(v) else collect(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun runsText(obj: JSONObject?): List<String> {
        val runs = obj?.optJSONArray("runs") ?: return emptyList()
        return (0 until runs.length()).map { runs.getJSONObject(it).optString("text") }
    }

    private fun bestThumb(container: JSONObject?): String {
        val arr = container?.optJSONArray("thumbnails") ?: return ""
        if (arr.length() == 0) return ""
        // Android Auto only renders small artwork tiles. Request a thumbnail
        // sized for those tiles instead of downloading the larger phone image.
        return arr.getJSONObject(arr.length() - 1).optString("url")
            .replace(Regex("=w\\d+-h\\d+.*$"), "=w256-h256")
    }

    private fun parseDuration(s: String): Long {
        if (!Regex("\\d+(:\\d+)+").matches(s)) return 210000L
        return s.split(":").fold(0L) { acc, p -> acc * 60 + p.toLong() } * 1000L
    }

    private fun parseResponsiveItem(r: JSONObject, idx: Int): SongItemClean? {
        val flex = r.optJSONArray("flexColumns") ?: return null
        fun col(i: Int) = flex.optJSONObject(i)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")

        val title = runsText(col(0)).firstOrNull() ?: return null
        var videoId = r.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
        if (videoId.isEmpty()) {
            videoId = r.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")
                ?.optString("videoId").orEmpty()
        }
        if (videoId.isEmpty()) return null

        val meta = runsText(col(1)).joinToString("").split("•").map { it.trim() }
            .filter { it.isNotEmpty() && it != "Song" && it != "Video" }
        val durStr = meta.lastOrNull { Regex("\\d+(:\\d+)+").matches(it) }.orEmpty()
        val nonDur = meta.filter { it != durStr }
        val artist = nonDur.firstOrNull() ?: "Unknown"
        val album = nonDur.getOrNull(1) ?: "YouTube Music"
        val thumb = bestThumb(
            r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
        )

        return SongItemClean(
            id = videoId, videoId = videoId, title = title, artist = artist, album = album,
            coverUrl = thumb.ifEmpty { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
            themeColor = paletteColors[idx % paletteColors.size],
            durationMs = if (durStr.isNotEmpty()) parseDuration(durStr) else 210000L
        )
    }

    private fun parseTwoRowItem(r: JSONObject, idx: Int): SongItemClean? {
        val title = runsText(r.optJSONObject("title")).firstOrNull() ?: return null
        val nav = r.optJSONObject("navigationEndpoint")
        val play = r.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")

        val watch = nav?.optJSONObject("watchEndpoint") ?: play?.optJSONObject("watchEndpoint")
        val videoId = watch?.optString("videoId").orEmpty()
        val playlistId = watch?.optString("playlistId").orEmpty()
            .ifEmpty { play?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId").orEmpty() }
            .ifEmpty { nav?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId").orEmpty() }
        if (videoId.isEmpty() && playlistId.isEmpty()) return null

        val subtitle = runsText(r.optJSONObject("subtitle")).joinToString("").trim()
        val thumb = bestThumb(
            r.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
        )

        return SongItemClean(
            id = videoId.ifEmpty { "pl_$playlistId" },
            videoId = videoId, playlistId = playlistId,
            title = title, artist = subtitle.ifEmpty { "YouTube Music" }, album = "YouTube Music",
            coverUrl = thumb.ifEmpty { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
            themeColor = paletteColors[idx % paletteColors.size]
        )
    }

    private fun parseSongs(json: JSONObject): List<SongItemClean> {
        val nodes = mutableListOf<JSONObject>()
        collect(json, "musicResponsiveListItemRenderer", nodes)
        return nodes.mapIndexedNotNull { i, r -> parseResponsiveItem(r, i) }.distinctBy { it.id }
    }

    private fun parseShelves(json: JSONObject): List<HomeShelf> {
        val nodes = mutableListOf<JSONObject>()
        collect(json, "musicCarouselShelfRenderer", nodes)
        return nodes.mapNotNull { shelf ->
            val title = runsText(
                shelf.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                    ?.optJSONObject("title")
            ).firstOrNull() ?: return@mapNotNull null
            val contents = shelf.optJSONArray("contents") ?: return@mapNotNull null
            val songs = mutableListOf<SongItemClean>()
            var isList = false
            for (i in 0 until contents.length()) {
                val c = contents.optJSONObject(i) ?: continue
                c.optJSONObject("musicResponsiveListItemRenderer")?.let { r ->
                    parseResponsiveItem(r, songs.size)?.let { songs += it; isList = true }
                }
                c.optJSONObject("musicTwoRowItemRenderer")?.let { r ->
                    parseTwoRowItem(r, songs.size)?.let { songs += it }
                }
            }
            if (songs.isEmpty()) null else HomeShelf(title, songs.distinctBy { it.id }, isList)
        }
    }
}

// ───────────────────────── VIEWMODEL ─────────────────────────

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(AppStateModel())
    val state: StateFlow<AppStateModel> = _state.asStateFlow()
    private val preferences = application.getSharedPreferences("vybe_app_settings", Application.MODE_PRIVATE)
    private val equalizerController = EqualizerController(preferences)
    private val screenBackStack = ArrayDeque<ScreenNav>()

    private val favoriteListener: () -> Unit = {
        _state.value = _state.value.copy(favoriteIds = Favorites.ids())
    }

    private var searchJob: Job? = null
    private var playJob: Job? = null
    private var radioSleepJob: Job? = null
    private var radioRecordingJob: Job? = null
    private var videoJob: Job? = null
    private var mixFadeJob: Job? = null
    private var mixTransitionJob: Job? = null
    private var autoMixFadedSongId: String? = null
    private var pendingSeekStartedAtMs: Long? = null
    private var pendingSeekTargetMs: Long = 0L
    private var pendingSeekWasPlaying = false
    private var pendingSeekDiscontinuity = false
    private val earlyEndRetries = mutableSetOf<String>()
    private var advertisedAudioEffectSessionId = C.AUDIO_SESSION_ID_UNSET
    private val signaledListenIds = mutableSetOf<String>()

    private val player: ExoPlayer = PlayerHolder.get(application)

    /** Clears KILO's pre-v2 local state exactly once for existing users, then shows setup again. */
    private fun runFirstUpdateResetIfNeeded(application: Application): Boolean {
        val completedSettings = application.getSharedPreferences("vybe_app_settings", Application.MODE_PRIVATE)
        val marker = "kilo_v2_first_update_reset_done"
        val wasSetupComplete = completedSettings.getBoolean(
            "setup_complete",
            completedSettings.getInt("setup_step", -1) >= 7
        )
        if (!wasSetupComplete || completedSettings.getBoolean(marker, false)) return false

        // Keep downloaded files where the user stored them; clear only KILO-managed state.
        application.getSharedPreferences("vybe_prefs", Application.MODE_PRIVATE).edit().clear().commit()
        completedSettings.edit().clear().commit()
        application.deleteDatabase("kilo_android_auto_browse.db")
        runCatching { File(application.cacheDir, "vybe_audio").deleteRecursively() }
        Favorites.clearForFirstUpdate()
        PlayedSongHistory.clear()
        HomeCache.set(emptyList())
        YtRepo.userAuthCookie = ""
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        completedSettings.edit()
            .putBoolean(marker, true)
            .putBoolean("setup_complete", false)
            .putInt("setup_step", 0)
            .putBoolean("personalization_pending", true)
            .commit()
        return true
    }

    private val playbackListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val currentMediaId = player.currentMediaItem?.mediaId.orEmpty()
            val radio = currentMediaId.startsWith("radio:")
            Log.d(
                if (radio) "KiloRadio" else "SeekBenchmark",
                "state=${playbackStateName(playbackState)} mediaId=$currentMediaId position=${player.currentPosition}ms " +
                    "buffered=${(player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)}ms " +
                    "bandwidth=${PlayerHolder.estimatedBandwidthKbps()}kbps " +
                    "playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying}"
            )
            _state.value = _state.value.copy(
                isLoadingStream = playbackState == Player.STATE_BUFFERING && player.playWhenReady,
                isPlaying = player.isPlaying
            )
            if (playbackState == Player.STATE_ENDED) {
                val ended = _state.value.activeSong
                PlayedSongHistory.recordSignal(ended, "complete")
                PlayedSongHistory.record(ended.id)
            }
            if (playbackState == Player.STATE_READY && player.playWhenReady && player.volume < 0.99f) {
                // Auto Mix fades can be interrupted by a track change. Recover the
                // shared player volume as soon as the new item is ready.
                fadePlayerVolume(1f, 500L, _state.value.activeSong.id)
            }
            updateAudioEffectSession(player.playWhenReady && playbackState in listOf(Player.STATE_BUFFERING, Player.STATE_READY))
            finishSeekMeasurementIfReady()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                pendingSeekDiscontinuity = true
                Log.d(
                    "SeekBenchmark",
                    "position discontinuity reason=SEEK from=${oldPosition.positionMs}ms " +
                        "to=${newPosition.positionMs}ms requested=${pendingSeekTargetMs}ms"
                )
                finishSeekMeasurementIfReady()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId ?: return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                val previousSong = _state.value.activeSong
                val expectedMs = previousSong.durationMs
                val lastKnownPositionMs = _state.value.currentPosMs
                if (expectedMs > 90_000L && lastKnownPositionMs < expectedMs * 0.75f) {
                    Log.w(
                        "VybeVM",
                        "Early automatic track transition: ${previousSong.videoId} at ${lastKnownPositionMs}ms; catalog duration ${expectedMs}ms"
                    )
                    val previousIndex = _state.value.currentIndex
                    val resumePositionMs = _state.value.currentPosMs.coerceAtLeast(0L)
                    if (earlyEndRetries.add(previousSong.id)) {
                        // Rebuild the affected MediaSource on retry. Reusing the
                        // prepared queue here would keep its already-resolved URL,
                        // defeating the stream invalidation above.
                        LosslessSource.invalidate(previousSong.videoId.ifBlank { previousSong.id })
                        FastStream.invalidate(previousSong.videoId)
                        loadTrack(
                            previousIndex,
                            autoPlay = true,
                            startPositionMs = resumePositionMs,
                            retryingEarlyEnd = true
                        )
                    } else {
                        player.pause()
                        player.setMediaItem(previousSong.toMediaItem(), resumePositionMs)
                        _state.value = _state.value.copy(
                            playlist = listOf(previousSong), currentIndex = 0,
                            progress = (resumePositionMs.toFloat() / previousSong.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f),
                            currentPosMs = resumePositionMs,
                            isPlaying = false, isLoadingStream = false
                        )
                        Toast.makeText(getApplication(), "This track ended early. Tap play to retry it.", Toast.LENGTH_LONG).show()
                    }
                    return
                }
            }
            val current = _state.value
            val metadata = mediaItem?.mediaMetadata
            val mediaTitle = metadata?.title?.toString()?.trim().orEmpty()
            val mediaArtist = metadata?.artist?.toString()?.trim().orEmpty()
            val idx = current.playlist.indexOfFirst { it.videoId == id || it.id == id }
                .takeIf { it >= 0 }
                ?: current.playlist.indexOfFirst {
                    mediaTitle.isNotBlank() && it.title.equals(mediaTitle, ignoreCase = true) &&
                        (mediaArtist.isBlank() || it.artist.equals(mediaArtist, ignoreCase = true))
                }
            if (idx >= 0) {
                val existing = current.playlist[idx]
                val exact = if (mediaTitle.isNotBlank() && mediaArtist.isNotBlank()) {
                    existing.copy(
                        title = mediaTitle,
                        artist = mediaArtist,
                        album = metadata?.albumTitle?.toString()?.takeIf(String::isNotBlank) ?: existing.album,
                        coverUrl = metadata?.artworkUri?.toString()?.takeIf(String::isNotBlank) ?: existing.coverUrl,
                        durationMs = metadata?.durationMs?.takeIf { it > 0L } ?: existing.durationMs
                    )
                } else existing
                val updatedPlaylist = current.playlist.toMutableList().also { it[idx] = exact }
                val updatedDownloads = current.downloadedSongs.map { if (it.id == exact.id) exact else it }
                _state.value = current.copy(
                    playlist = updatedPlaylist,
                    downloadedSongs = updatedDownloads,
                    currentIndex = idx
                )
            }
            SessionStore.record(mediaItem)
            val activeSong = _state.value.activeSong
            if (_state.value.autoMixEnabled && player.playWhenReady && player.volume < 0.99f) {
                fadePlayerVolume(1f, 700L, activeSong.id)
            } else if (!_state.value.autoMixEnabled && player.volume <= 0.001f) {
                // A cancelled transition fade must not leave later tracks silent.
                player.volume = 1f
            }
            // An automatic transition means the user was listening through the previous track.
            // Keep playback intent across track boundaries, including when a stream briefly buffers.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) player.play()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d("SeekBenchmark", "isPlaying=$isPlaying position=${player.currentPosition}ms")
            finishSeekMeasurementIfReady()
            _state.value = _state.value.copy(isPlaying = isPlaying)
            if (isPlaying) prefetchNextStream(_state.value.currentIndex)
        }

        override fun onPlayerError(error: PlaybackException) {
            val radio = player.currentMediaItem?.mediaId.orEmpty().startsWith("radio:")
            Log.e(if (radio) "KiloRadio" else "SeekBenchmark", "player error ${error.errorCodeName} at ${player.currentPosition}ms", error)
            val song = _state.value.activeSong
            val retrying = PlayerHolder.isRetrying(song.videoId.ifBlank { song.id })
            _state.value = _state.value.copy(isPlaying = false, isLoadingStream = retrying)
            if (!retrying) {
                Toast.makeText(getApplication(), "Couldn’t play ${song.title}. Tap play to retry.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finishSeekMeasurementIfReady() {
        val startedAt = pendingSeekStartedAtMs ?: return
        if (!pendingSeekDiscontinuity || player.playbackState != Player.STATE_READY) return
        if (pendingSeekWasPlaying && !player.isPlaying) return
        Log.d(
            "SeekBenchmark",
            "seek complete target=${pendingSeekTargetMs}ms actual=${player.currentPosition}ms " +
                "latency=${System.currentTimeMillis() - startedAt}ms " +
                "buffered=${(player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)}ms"
        )
        pendingSeekStartedAtMs = null
        pendingSeekDiscontinuity = false
    }

    private fun playbackStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    init {
        LyricsRepository.loadProviderOrder(application)
        PlayedSongHistory.init(application)
        Favorites.init(application)
        val firstUpdateReset = runFirstUpdateResetIfNeeded(application)
        Favorites.addListener(favoriteListener)
        SessionStore.init(application)
        FastStream.warmUp()
        player.addListener(playbackListener)

        val savedCustomizations = UI_CUSTOMIZATION_OPTIONS.associate { option ->
            option.key to preferences.getBoolean("ui_${option.key}", option.enabledByDefault)
        }
        _state.value = _state.value.copy(
            isSetupComplete = preferences.getBoolean(
                "setup_complete",
                preferences.getInt("setup_step", -1) >= 7
            ),
            setupStep = preferences.getInt("setup_step", 0),
            downloadDirectoryUri = preferences.getString("download_directory_uri", null),
            downloadedSongs = loadDownloadedSongs(),
            uiCustomizations = savedCustomizations,
            selectedPlayerStyleId = preferences.getString("player_style_id", PLAYER_STYLE_PRESETS.first().id) ?: PLAYER_STYLE_PRESETS.first().id,
            selectedMood = preferences.getString("discovery_mood", "Discover") ?: "Discover",
            listenerProfile = ProfileEngine.load(preferences),
            showPersonalizationWizard = preferences.getBoolean("personalization_pending", false),
            accountsList = loadAccounts(),
            activeAccountId = preferences.getString("active_account_id", "1") ?: "1",
            savedPlaylists = loadSavedPlaylists(),
            favoriteIds = Favorites.ids(),
            radioFavorites = RadioStationStore.load(application, "radio_favorites"),
            radioRecentStations = RadioStationStore.load(application, "radio_recent_stations"),
            customRadioStations = RadioStationStore.load(application, "radio_custom_stations"),
            lastRadioStation = RadioStationStore.loadLast(application),
            notRecommendedIds = preferences.getStringSet("not_recommended_ids", emptySet())?.toSet() ?: emptySet(),
            activeSkin = SkinTheme.values().getOrElse(preferences.getInt("active_skin", 0)) { SkinTheme.SUNSET_GLASS },
            displayName = preferences.getString("display_name", "Arnav") ?: "Arnav",
            autoMixEnabled = preferences.getBoolean("auto_mix_enabled", false),
            autoConnectCar = preferences.getBoolean("auto_connect_car_enabled", true),
            autoLayoutMode = normalizeAutoLayoutMode(preferences.getString("auto_layout_mode", AUTO_LAYOUT_MODES.first())),
            autoPlayOnCarConnect = preferences.getBoolean("auto_play_car_connect", false),
            pauseForCalls = preferences.getBoolean("pause_for_calls", true),
            filterMatureContent = preferences.getBoolean("filter_mature_content", true),
            losslessSourceUrl = LosslessSource.root(application),
            losslessSourceEnabled = LosslessSource.isEnabled(application),
            losslessSourceHasToken = LosslessSource.hasToken(application),
            losslessSourceStatus = preferences.getString("lossless_source_status", "Optional: connect a compatible FLAC source") ?: "Optional: connect a compatible FLAC source"
        )
        preferences.edit().putString("auto_layout_mode", _state.value.autoLayoutMode).apply()
        val savedRegion = preferences.getString("music_region_code", "AUTO") ?: "AUTO"
        YtRepo.setRegionCode(savedRegion)
        _state.value = _state.value.copy(musicRegionCode = savedRegion)

        try {
            NewPipe.init(
                NewPipeDownloader(
                    OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .build()
                )
            )
        } catch (e: Exception) {
            Log.e("VybeVM", "NewPipe init failed", e)
        }

        val existingCookies = try {
            CookieManager.getInstance().getCookie("https://music.youtube.com") ?: ""
        } catch (_: Exception) {
            ""
        }
        if (!firstUpdateReset && existingCookies.contains("SAPISID")) {
            YtRepo.userAuthCookie = existingCookies
            val activeAccount = _state.value.accountsList.firstOrNull { it.id == _state.value.activeAccountId }
            if (activeAccount != null && activeAccount.cookie != existingCookies) {
                val updatedAccounts = _state.value.accountsList.map {
                    if (it.id == activeAccount.id) it.copy(cookie = existingCookies) else it
                }
                persistAccounts(updatedAccounts, activeAccount.id)
                _state.value = _state.value.copy(accountsList = updatedAccounts)
            }
            _state.value = _state.value.copy(isLoggedIn = true, userAuthCookie = existingCookies)
        }

        loadHome()
        if (_state.value.downloadDirectoryUri != null) scanDownloadedFolder(showMessage = false)

        viewModelScope.launch {
            while (true) {
                if (player.isPlaying) {
                    val realDur = if (player.duration > 0) player.duration else _state.value.currentDurationMs
                    val pos = player.currentPosition
                    _state.value = _state.value.copy(
                        isPlaying = true,
                        currentPosMs = pos,
                        currentDurationMs = realDur,
                        progress = (pos.toFloat() / realDur.toFloat()).coerceIn(0f, 1f)
                    )
                    val remaining = realDur - pos
                    val songId = _state.value.activeSong.id
                    if (pos >= 30_000L && signaledListenIds.add(songId)) {
                        val song = _state.value.activeSong
                        PlayedSongHistory.record(song.id)
                        PlayedSongHistory.recordSignal(song, "listen", pos.toFloat())
                    }
                    if (_state.value.autoMixEnabled && remaining in 1L..1_500L && autoMixFadedSongId != songId) {
                        autoMixFadedSongId = songId
                        fadePlayerVolume(to = 0f, durationMs = remaining.coerceAtLeast(250L), songId = songId)
                    }
                }
                delay(200)
            }
        }
    }

    private fun startPlaybackService() {
        runCatching {
            val app = getApplication<Application>()
            ContextCompat.startForegroundService(app, Intent(app, VybeMediaService::class.java))
        }
    }

    private fun prefetchStream(videoId: String) {
        if (videoId.isBlank()) return
        PlayerHolder.prefetch(getApplication(), videoId)
    }

    private fun prefetchNextStream(index: Int) {
        val songs = _state.value.playlist
        if (songs.size < 2) return
        val next = songs[(index + 1) % songs.size]
        LosslessSource.register(next)
        if (next.videoId.isNotBlank()) prefetchStream(next.videoId)
    }

    fun reloadHome() = loadHome()
    fun playFromAssistantQuery(query: String) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return
        viewModelScope.launch {
            val state = _state.value
            val matches = withContext(Dispatchers.IO) { YtRepo.searchSongs(cleanQuery) }.ifEmpty {
                state.downloadedSongs.filter { song ->
                    song.title.contains(cleanQuery, ignoreCase = true) ||
                        song.artist.contains(cleanQuery, ignoreCase = true) ||
                        "${song.artist} ${song.title}".contains(cleanQuery, ignoreCase = true)
                }
            }
            val playable = matches.filter { it.videoId.isNotBlank() || it.isDownloaded }
            val selected = playable.firstOrNull()
            if (selected != null) {
                playSongFromShelf(selected, playable)
            } else {
                Toast.makeText(getApplication(), "No matching song found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadHome() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSearching = true)
            val launchNumber = preferences.getInt("home_launch_number", 0) + 1
            preferences.edit().putInt("home_launch_number", launchNumber).apply()
            val profile = _state.value.listenerProfile
            val baseInterests = (Favorites.songs() + PlayedSongHistory.recommendationArtists().map { artist ->
                SongItemClean(id = "artist:$artist", title = "", artist = artist, album = "", coverUrl = "")
            } + profile.followedArtists.map { artist ->
                SongItemClean(id = "followed:$artist", title = "", artist = artist, album = "", coverUrl = "")
            })
                .filter { it.artist.isNotBlank() }
                .groupingBy { it.artist.trim() }
                .eachCount()
                .entries.sortedByDescending { it.value }.map { it.key }.distinct()
            val discoveryThemes = listOf("new indie artists", "fresh alternative songs", "new electronic music", "rising pop songs", "underground R&B discoveries")
            val selectedMood = _state.value.selectedMood.takeUnless { it.equals("Discover", ignoreCase = true) }
            val activeMood = selectedMood ?: profile.favoriteMoods.firstOrNull()
            val activeGenre = profile.favoriteGenres.firstOrNull()
            val recommendationQuery = if (baseInterests.isNotEmpty()) {
                "songs like ${baseInterests[(launchNumber - 1) % baseInterests.size]}"
            } else if (activeMood != null) {
                moodDiscoveryQuery(activeMood, activeGenre)
            } else if (activeGenre != null) {
                genreDiscoveryQuery(activeGenre)
            } else {
                discoveryThemes[(launchNumber - 1) % discoveryThemes.size]
            }
            val freshThemes = listOf(
                "new music releases",
                "new indie artists",
                "fresh alternative songs",
                "new electronic music",
                "rising pop songs",
                "underground R&B discoveries",
                "new regional music",
                "emerging artists"
            )
            val freshQuery = when {
                activeMood != null -> moodDiscoveryQuery(activeMood, activeGenre)
                activeGenre != null -> genreDiscoveryQuery(activeGenre)
                else -> freshThemes[(launchNumber - 1) % freshThemes.size]
            }
            val hasPersonalizedInterest = baseInterests.isNotEmpty() || selectedMood != null ||
                profile.favoriteMoods.isNotEmpty() || profile.favoriteGenres.isNotEmpty()
            val loadResult = coroutineScope {
                val homeTask = async(Dispatchers.IO) { YtRepo.fetchHomeCatalog() }
                val historyTask = async(Dispatchers.IO) {
                    Triple(PlayedSongHistory.ids(), PlayedSongHistory.discoveredIds(), PlayedSongHistory.discoveredButUnplayed())
                }
                val completeTask = async(Dispatchers.IO) { PlayedSongHistory.isHomeCatalogComplete() }
                val similarTask = async(Dispatchers.IO) {
                    if (!hasPersonalizedInterest) emptyList() else {
                        YtRepo.searchSongs(recommendationQuery).let { results ->
                            val moodFiltered = activeMood?.let { filterMoodTitleMatches(results, it) } ?: results
                            activeGenre?.let { filterGenreTitleMatches(moodFiltered, it) } ?: moodFiltered
                        }
                    }
                }
                val freshTask = async(Dispatchers.IO) {
                    YtRepo.searchSongs(freshQuery).let { results ->
                        val moodFiltered = activeMood?.let { filterMoodTitleMatches(results, it) } ?: results
                        activeGenre?.let { filterGenreTitleMatches(moodFiltered, it) } ?: moodFiltered
                    }
                }
                Triple(
                    homeTask.await(),
                    historyTask.await() to completeTask.await(),
                    similarTask.await() to freshTask.await()
                )
            }
            val fetched = loadResult.first
            val historyState = loadResult.second.first
            val playedBefore = historyState.first
            val discoveredBefore = historyState.second
            val dormantDiscovered = historyState.third
            val alreadyComplete = loadResult.second.second
            val similarResults = loadResult.third.first
            val freshResults = loadResult.third.second
            val blockedIds = playedBefore + _state.value.notRecommendedIds
            fun acceptable(song: SongItemClean) = song.videoId.isNotBlank() && song.id !in blockedIds &&
                (!_state.value.filterMatureContent || !song.isExplicit)

            val shelfLimit = when (profile.discoveryLevel) {
                "Conservative" -> 10
                "Explorer" -> 24
                "Adventurer" -> 30
                else -> 20
            }
            val similarSongs = similarResults.asSequence()
                .filter(::acceptable)
                .distinctBy { it.id }
                .take(shelfLimit).toList()
            val similarIds = similarSongs.mapTo(mutableSetOf()) { it.id }
            val discoveredSongs = dormantDiscovered.asSequence()
                .filter(::acceptable)
                .filter { it.id !in similarIds }
                .distinctBy { it.id }
                .sortedBy { it.title.lowercase() }
                .take(shelfLimit).toList()
            val discoveredIds = (discoveredBefore + discoveredSongs.map { it.id }).toSet()
            val homeSongs = fetched.shelves.flatMap { it.songs }
            val freshSongs = (freshResults + homeSongs).asSequence()
                .filter(::acceptable)
                .filter { it.id !in discoveredIds && it.id !in similarIds }
                .distinctBy { it.id }
                .shuffled()
                .take(shelfLimit + 4).toList()

            val shelves = buildList {
                if (similarSongs.isNotEmpty()) add(HomeShelf(
                    if (baseInterests.isEmpty() && activeMood != null) "For your $activeMood mood" else "Similar to your favorites",
                    similarSongs,
                    false
                ))
                if (discoveredSongs.isNotEmpty()) add(HomeShelf("Discovered, not played", discoveredSongs, false))
                if (freshSongs.isNotEmpty()) add(HomeShelf("Fresh discoveries", freshSongs, false))
                if (isEmpty() && alreadyComplete) {
                    // Familiar tracks are a failsafe only after a complete Home catalog scan.
                    val familiar = (SessionStore.recentSongs() + Favorites.songs())
                        .asSequence()
                        .filter { song -> song.videoId.isNotBlank() && song.id !in _state.value.notRecommendedIds &&
                            (!_state.value.filterMatureContent || !song.isExplicit) }
                        .distinctBy { it.id }.shuffled().take(20).toList()
                    if (familiar.isNotEmpty()) add(HomeShelf("Familiar favorites", familiar, false))
                }
            }
            val similarShelfSongs = shelves.firstOrNull { it.title == "Similar to your favorites" }?.songs.orEmpty()
            val discoveredShelfSongs = shelves.firstOrNull { it.title == "Discovered, not played" }?.songs.orEmpty()
            val freshShelfSongs = shelves.firstOrNull { it.title == "Fresh discoveries" }?.songs.orEmpty()
            withContext(Dispatchers.IO) {
                if (fetched.complete) PlayedSongHistory.markHomeCatalogComplete()
                PlayedSongHistory.markDiscovered(homeSongs, "home_catalog")
                PlayedSongHistory.markDiscovered(similarShelfSongs, "similar")
                PlayedSongHistory.markDiscovered(discoveredShelfSongs, "discovered")
                PlayedSongHistory.markDiscovered(freshShelfSongs, "fresh")
            }
            HomeCache.set(shelves)
            val all = shelves.flatMap { it.songs }.filter { it.videoId.isNotEmpty() }.distinctBy { it.id }
            val picks = shelves.firstOrNull()?.songs.orEmpty()
            _state.value = _state.value.copy(
                homeShelves = shelves,
                quickPicks = picks,
                playlist = all,
                currentIndex = 0,
                isSearching = false
            )
            all.take(4).forEach { song ->
                LosslessSource.register(song)
                song.videoId.takeIf(String::isNotBlank)?.let(::prefetchStream)
            }
        }
    }
    fun onSearchQueryChanged(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.value = _state.value.copy(
                searchResults = emptyList(), communityPlaylistResults = emptyList(), artistSearchResults = emptyList(), isSearching = false
            )
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            _state.value = _state.value.copy(isSearching = true)
            val (results, playlists, artists) = coroutineScope {
                val songsRequest = async(Dispatchers.IO) { YtRepo.searchSongs(query) }
                val playlistsRequest = async(Dispatchers.IO) { YtRepo.searchCommunityPlaylists(query) }
                val artistsRequest = async(Dispatchers.IO) { YtRepo.searchArtists(query) }
                Triple(songsRequest.await(), playlistsRequest.await(), artistsRequest.await())
            }
            val visibleResults = results.filter { !_state.value.filterMatureContent || !it.isExplicit }
            _state.value = _state.value.copy(
                searchResults = visibleResults,
                communityPlaylistResults = playlists,
                artistSearchResults = artists,
                isSearching = false
            )
            results.take(3).forEach { song ->
                LosslessSource.register(song)
                song.videoId.takeIf(String::isNotBlank)?.let(::prefetchStream)
            }
        }
    }

    fun openArtist(artist: ArtistItem) {
        screenBackStack.addLast(ScreenNav.SEARCH)
        _state.value = _state.value.copy(
            activeArtist = artist, artistAlbums = emptyList(), artistSongs = emptyList(),
            selectedArtistAlbumTitle = null, currentScreen = ScreenNav.ARTIST_DETAIL, isSearching = true
        )
        viewModelScope.launch {
            val catalog = withContext(Dispatchers.IO) { YtRepo.fetchArtistCatalog(artist) }
            if (_state.value.activeArtist?.id != artist.id) return@launch
            _state.value = _state.value.copy(
                activeArtist = catalog.first, artistAlbums = catalog.second, artistSongs = catalog.third,
                isSearching = false
            )
        }
    }

    fun openArtistAlbum(album: SongItemClean) {
        val artist = _state.value.activeArtist ?: return
        if (album.albumBrowseId.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSearching = true, selectedArtistAlbumTitle = album.title)
            val tracks = withContext(Dispatchers.IO) { YtRepo.fetchAlbumTracks(album.albumBrowseId, artist.name) }
            if (_state.value.activeArtist?.id == artist.id) {
                _state.value = _state.value.copy(artistSongs = tracks, isSearching = false)
            }
        }
    }

    fun showAllArtistSongs() {
        val artist = _state.value.activeArtist ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSearching = true, selectedArtistAlbumTitle = null)
            val catalog = withContext(Dispatchers.IO) { YtRepo.fetchArtistCatalog(artist) }
            if (_state.value.activeArtist?.id == artist.id) {
                _state.value = _state.value.copy(artistSongs = catalog.third, isSearching = false)
            }
        }
    }

    fun setMusicRegion(code: String) {
        val normalized = code.uppercase().takeIf { it == "AUTO" || it.matches(Regex("[A-Z]{2}")) } ?: "AUTO"
        preferences.edit().putString("music_region_code", normalized).apply()
        YtRepo.setRegionCode(normalized)
        _state.value = _state.value.copy(musicRegionCode = normalized)
        reloadHome()
    }

    fun playSongFromShelf(song: SongItemClean, shelfList: List<SongItemClean>) {
        if (song.videoId.isEmpty() && song.albumBrowseId.isNotEmpty()) {
            viewModelScope.launch {
                _state.value = _state.value.copy(isLoadingStream = true)
                val tracks = withContext(Dispatchers.IO) { YtRepo.fetchAlbumTracks(song.albumBrowseId, song.artist) }
                if (tracks.isNotEmpty()) {
                    _state.value = _state.value.copy(playlist = tracks, currentIndex = 0)
                    loadTrack(0, autoPlay = true)
                } else {
                    _state.value = _state.value.copy(isLoadingStream = false)
                    Toast.makeText(getApplication(), "Could not load ${song.title}", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        if (song.videoId.isEmpty() && song.playlistId.isNotEmpty()) {
            viewModelScope.launch {
                _state.value = _state.value.copy(isLoadingStream = true)
                val tracks = withContext(Dispatchers.IO) { YtRepo.fetchPlaylistTracks(song.playlistId) }
                if (tracks.isNotEmpty()) {
                    _state.value = _state.value.copy(playlist = tracks, currentIndex = 0)
                    loadTrack(0, autoPlay = true)
                } else {
                    _state.value = _state.value.copy(isLoadingStream = false)
                }
            }
            return
        }
        val queue = shelfList.filter { it.videoId.isNotEmpty() }.ifEmpty { listOf(song) }
        queue.forEach { LosslessSource.register(it) }
        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        _state.value = _state.value.copy(playlist = queue, currentIndex = index)
        loadTrack(index, autoPlay = true)
    }

    fun loadTrack(
        index: Int,
        autoPlay: Boolean = true,
        startPositionMs: Long = 0L,
        retryingEarlyEnd: Boolean = false
    ) {
        val list = _state.value.playlist
        if (list.isEmpty()) return
        val idx = (index + list.size) % list.size
        val song = list[idx]
        if (!retryingEarlyEnd) earlyEndRetries.remove(song.id)
_state.value = _state.value.copy(
            currentIndex = idx,
            progress = (startPositionMs.toFloat() / song.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f),
            currentPosMs = startPositionMs,
            currentDurationMs = song.durationMs,
            isLoadingStream = true,
            videoMode = false
        )

        // Keep Media3's existing timeline for skips within the same queue. Replacing
        // all media items forces another source resolution and creates an avoidable
        // buffering gap on every manual next/previous tap.
        val queueIsPrepared = !retryingEarlyEnd && player.playbackState == Player.STATE_READY
        val queueAlreadyLoaded = queueIsPrepared && player.mediaItemCount == list.size &&
            (0 until player.mediaItemCount).all { mediaIndex ->
                val expectedId = list[mediaIndex].videoId.ifBlank { list[mediaIndex].id }
                player.getMediaItemAt(mediaIndex).mediaId == expectedId
            }
        if (queueAlreadyLoaded) {
            player.seekTo(idx, startPositionMs)
            player.volume = if (_state.value.autoMixEnabled) 0f else 1f
            if (autoPlay) player.play()
            if (autoPlay && _state.value.autoMixEnabled) {
                fadePlayerVolume(1f, 700L, song.id)
            }
            _state.value = _state.value.copy(
                isLoadingStream = player.playbackState == Player.STATE_BUFFERING,
                isPlaying = player.playWhenReady
            )
            prefetchNextStream(idx)
            return
        }

        playJob?.cancel()
        videoJob?.cancel()
        playJob = viewModelScope.launch {
            try {
                startPlaybackService()
                val mediaItems = list.map { it.toMediaItem() }
                player.setMediaItems(mediaItems, idx, startPositionMs)
                if (_state.value.autoMixEnabled) {
                    player.volume = 0f
                    fadePlayerVolume(1f, 700L, song.id)
                } else {
                    player.volume = 1f
                }
                player.prepare()
                if (autoPlay) player.play()
                (LosslessSource.peek(song.videoId) ?: FastStream.peek(song.videoId))?.let { info ->
                    val updated = _state.value.playlist.map { if (it.id == song.id) it.copy(codecInfo = info.codec) else it }
                    _state.value = _state.value.copy(playlist = updated)
                }
                prefetchNextStream(idx)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("VybeVM", "Could not prepare stream for ${song.videoId}", error)
                _state.value = _state.value.copy(isLoadingStream = false, isPlaying = false)
            }
        }
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            _state.value = _state.value.copy(isPlaying = false)
        } else {
            startPlaybackService()
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED || player.mediaItemCount == 0) {
                val radio = _state.value.lastRadioStation?.takeIf { _state.value.activeSong.id == "radio:" + it.id }
                if (radio != null) playRadioStation(radio)
                else loadTrack(
                    _state.value.currentIndex,
                    autoPlay = true,
                    startPositionMs = _state.value.currentPosMs.coerceAtLeast(0L)
                )
            } else {
                player.play()
                _state.value = _state.value.copy(isPlaying = true)
            }
        }
    }

    private fun mixTransitionTo(index: Int) {
        val songId = _state.value.activeSong.id
        if (!_state.value.autoMixEnabled || !player.isPlaying) {
            loadTrack(index, autoPlay = true)
            return
        }
        mixTransitionJob?.cancel()
        fadePlayerVolume(0f, 300L, songId)
        mixTransitionJob = viewModelScope.launch {
            mixFadeJob?.join()
            if (_state.value.activeSong.id == songId) loadTrack(index, autoPlay = true)
        }
    }

    fun nextTrack() {
        if (_state.value.activeSong.id.startsWith("radio:")) return
        mixTransitionTo(_state.value.currentIndex + 1)
    }

    fun prevTrack() {
        if (_state.value.activeSong.id.startsWith("radio:")) return
        mixTransitionTo(_state.value.currentIndex - 1)
    }

    fun seekTo(frac: Float) {
        val dur = if (player.duration > 0) player.duration else _state.value.currentDurationMs
        if (dur <= 0L || player.currentMediaItem == null) return
        val target = (dur * frac.coerceIn(0f, 1f)).toLong().coerceIn(0L, dur)
        val shouldResume = player.playWhenReady
        pendingSeekStartedAtMs = System.currentTimeMillis()
        pendingSeekTargetMs = target
        pendingSeekWasPlaying = shouldResume
        pendingSeekDiscontinuity = false
        Log.d(
            "SeekBenchmark",
            "seek requested from=${player.currentPosition}ms to=${target}ms " +
                "buffered=${(player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)}ms " +
                "playWhenReady=$shouldResume"
        )
        // A manual seek overrides any in-progress Auto Mix fade or pending skip.
        mixTransitionJob?.cancel()
        mixFadeJob?.cancel()
        autoMixFadedSongId = null
        player.volume = 1f
        player.seekTo(target)
        if (shouldResume) player.play()
        _state.value = _state.value.copy(progress = target.toFloat() / dur.toFloat(), currentPosMs = target)
    }

    fun closeAppCompletely() {
        runCatching {
            val app = getApplication<Application>()
            app.sendBroadcast(Intent(ACTION_CLOSE_APP).setPackage(app.packageName))
            player.stop()
        }
        exitProcess(0)
    }

    fun openWebLogin() { _state.value = _state.value.copy(showWebLoginDialog = true) }
    fun closeWebLogin() { _state.value = _state.value.copy(showWebLoginDialog = false) }
    fun toggleAccountManager(show: Boolean) { _state.value = _state.value.copy(showAccountManagerDialog = show) }

    fun addNewAccount(accountName: String, cookie: String) {
        val newAcc = UserProfileAccount(
            id = System.currentTimeMillis().toString(),
            name = accountName,
            profileImage = "https://picsum.photos/seed/$accountName/200",
            cookie = cookie,
            favoriteGenre = _state.value.selectedMood
        )
        YtRepo.userAuthCookie = cookie
        val accounts = _state.value.accountsList + newAcc
        persistAccounts(accounts, newAcc.id)
        _state.value = _state.value.copy(
            accountsList = accounts,
            activeAccountId = newAcc.id,
            userAuthCookie = cookie,
            isLoggedIn = cookie.contains("SAPISID"),
            showAccountManagerDialog = false,
            showWebLoginDialog = false
        )
        loadHome()
    }

    fun switchAccount(accountId: String) {
        val acc = _state.value.accountsList.find { it.id == accountId } ?: return
        YtRepo.userAuthCookie = acc.cookie
        persistAccounts(_state.value.accountsList, acc.id)
        _state.value = _state.value.copy(
            activeAccountId = acc.id,
            userAuthCookie = acc.cookie,
            isLoggedIn = acc.cookie.contains("SAPISID"),
            selectedMood = acc.favoriteGenre,
            showAccountManagerDialog = false
        )
        preferences.edit().putString("discovery_mood", acc.favoriteGenre).apply()
        loadHome()
    }

    fun onWebLoginSuccess(cookies: String) {
        if (_state.value.isLoggedIn && _state.value.userAuthCookie == cookies) return
        addNewAccount("Account ${_state.value.accountsList.size + 1}", cookies)
    }

    fun setLibraryTab(tabIndex: Int) { _state.value = _state.value.copy(libraryTab = tabIndex) }

    fun setFullScreenPlayer(open: Boolean) {
        _state.value = _state.value.copy(isFullScreenPlayerOpen = open, playerOverlayTab = OverlayTab.NONE)
    }

    fun activePlayerForVideoView(): ExoPlayer = player

    fun setDisplayName(value: String) {
        val name = value.take(32)
        preferences.edit().putString("display_name", name).apply()
        _state.value = _state.value.copy(displayName = name)
    }

    fun setDiscoveryMood(value: String) {
        val mood = value.trim().take(48).ifBlank { "Discover" }
        preferences.edit().putString("discovery_mood", mood).apply()
        val accounts = _state.value.accountsList.map { account ->
            if (account.id == _state.value.activeAccountId) account.copy(favoriteGenre = mood) else account
        }
        persistAccounts(accounts, _state.value.activeAccountId)
        _state.value = _state.value.copy(selectedMood = mood, accountsList = accounts)
        loadHome()
    }

    fun openPersonalizationWizard() {
        _state.value = _state.value.copy(showPersonalizationWizard = true)
    }

    fun skipPersonalizationWizard() {
        preferences.edit().putBoolean("personalization_pending", false).apply()
        _state.value = _state.value.copy(showPersonalizationWizard = false)
    }

    fun saveListenerProfile(profile: ListenerProfile, finishSetup: Boolean = false) {
        val cleaned = profile.copy(
            favoriteGenres = profile.favoriteGenres.intersect(PERSONALIZATION_GENRES.toSet()),
            followedArtists = profile.followedArtists.map(String::trim).filter(String::isNotBlank).distinct().take(50),
            favoriteMoods = profile.favoriteMoods.intersect(PERSONALIZATION_MOODS.toSet())
        )
        ProfileEngine.save(preferences, cleaned)
        val audioSnapshot = equalizerController.setPreferredProfile(cleaned.audioPreference)
        preferences.edit().apply {
            putBoolean("personalization_pending", false)
            if (finishSetup) {
                putBoolean("setup_complete", true)
                putInt("setup_step", 11)
            }
        }.apply()
        val activeMood = cleaned.favoriteMoods.firstOrNull() ?: cleaned.favoriteGenres.firstOrNull() ?: "Discover"
        preferences.edit().putString("discovery_mood", activeMood).apply()
        val accounts = _state.value.accountsList.map { account ->
            if (account.id == _state.value.activeAccountId) account.copy(favoriteGenre = activeMood) else account
        }
        persistAccounts(accounts, _state.value.activeAccountId)
        _state.value = _state.value.copy(
            isSetupComplete = finishSetup || _state.value.isSetupComplete,
            setupStep = if (finishSetup) 11 else _state.value.setupStep,
            currentScreen = if (finishSetup) ScreenNav.HOME else _state.value.currentScreen,
            listenerProfile = cleaned,
            equalizerSnapshot = audioSnapshot,
            selectedMood = activeMood,
            accountsList = accounts,
            showPersonalizationWizard = false,
            profileArtistSuggestions = emptyList()
        )
        loadHome()
    }

    fun toggleProfileGenre(genre: String) {
        val current = _state.value.listenerProfile
        val genres = current.favoriteGenres.toMutableSet().apply { if (!add(genre)) remove(genre) }
        val profile = current.copy(favoriteGenres = genres)
        ProfileEngine.save(preferences, profile)
        _state.value = _state.value.copy(listenerProfile = profile)
    }

    fun toggleProfileMood(mood: String) {
        val current = _state.value.listenerProfile
        val moods = current.favoriteMoods.toMutableSet().apply { if (!add(mood)) remove(mood) }
        val profile = current.copy(favoriteMoods = moods)
        ProfileEngine.save(preferences, profile)
        _state.value = _state.value.copy(listenerProfile = profile)
    }

    fun searchProfileArtists(query: String) {
        if (query.isBlank()) {
            _state.value = _state.value.copy(profileArtistSuggestions = emptyList())
            return
        }
        viewModelScope.launch {
            val artists = withContext(Dispatchers.IO) { YtRepo.searchArtists(query) }
            _state.value = _state.value.copy(profileArtistSuggestions = artists)
        }
    }

    fun toggleFollowedArtist(name: String) {
        val current = _state.value.listenerProfile
        val artists = current.followedArtists.toMutableList()
        if (!artists.remove(name)) artists.add(name)
        val profile = current.copy(followedArtists = artists.take(50))
        ProfileEngine.save(preferences, profile)
        _state.value = _state.value.copy(listenerProfile = profile)
    }

    fun setProfileChoices(discovery: String? = null, audio: String? = null, androidAuto: String? = null) {
        val current = _state.value.listenerProfile
        val profile = current.copy(
            discoveryLevel = discovery ?: current.discoveryLevel,
            audioPreference = audio ?: current.audioPreference,
            androidAutoUsage = androidAuto ?: current.androidAutoUsage
        )
        ProfileEngine.save(preferences, profile)
        _state.value = _state.value.copy(listenerProfile = profile)
    }

    fun resetRecommendations() {
        PlayedSongHistory.resetRecommendationSignals()
        preferences.edit().remove("not_recommended_ids").apply()
        _state.value = _state.value.copy(notRecommendedIds = emptySet())
        HomeCache.set(emptyList())
        loadHome()
    }

    fun rebuildProfile() {
        val profile = _state.value.listenerProfile
        val artists = (profile.followedArtists + Favorites.songs().map { it.artist } + PlayedSongHistory.recommendationArtists())
            .filter(String::isNotBlank).distinct().take(50)
        saveListenerProfile(profile.copy(followedArtists = artists))
    }

    fun exportProfile() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, ProfileEngine.export(_state.value.listenerProfile))
        }
        runCatching {
            getApplication<Application>().startActivity(
                Intent.createChooser(send, "Export KILO profile").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun setAutoMixEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("auto_mix_enabled", enabled).apply()
        _state.value = _state.value.copy(autoMixEnabled = enabled)
        if (!enabled) {
            mixFadeJob?.cancel()
            player.volume = 1f
        }
    }

    fun toggleAutoConnectCar() {
        val enabled = !_state.value.autoConnectCar
        preferences.edit().putBoolean("auto_connect_car_enabled", enabled).apply()
        _state.value = _state.value.copy(autoConnectCar = enabled)
    }

    fun openAndroidAuto() {
        val app = getApplication<Application>()
        val autoPackage = "com.google.android.projection.gearhead"
        val launchIntent = app.packageManager.getLaunchIntentForPackage(autoPackage)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(launchIntent) }
            return
        }
        Toast.makeText(app, "Connect to your car to launch VYBE on Android Auto.", Toast.LENGTH_LONG).show()
    }

    private fun fadePlayerVolume(to: Float, durationMs: Long, songId: String) {
        mixFadeJob?.cancel()
        val from = player.volume
        mixFadeJob = viewModelScope.launch {
            val steps = (durationMs / 50L).coerceAtLeast(1L)
            for (step in 1L..steps) {
                if (_state.value.activeSong.id != songId) {
                    // The fade owner changed tracks. Do not strand the new track
                    // at the previous track's fade-out volume.
                    if (_state.value.autoMixEnabled && player.playWhenReady) player.volume = 1f
                    return@launch
                }
                val fraction = step.toFloat() / steps.toFloat()
                player.volume = from + (to - from) * fraction
                delay(50)
            }
            player.volume = to
        }
    }

    private suspend fun fetchVideoStream(videoId: String): VideoStreamInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val info = NPStreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val dashVideo = info.videoStreams.firstOrNull {
                it.deliveryMethod == DeliveryMethod.DASH && it.isUrl && it.manifestUrl?.startsWith("http") == true
            }
            if (dashVideo != null) {
                return@runCatching VideoStreamInfo("", null, "Mozilla/5.0", dashVideo.manifestUrl)
            }
            val videos = info.videoStreams.filter {
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.content.isNotEmpty()
            }
            val muxed = videos.filterNot { it.isVideoOnly }.maxByOrNull { it.height }
            if (muxed != null) {
                VideoStreamInfo(muxed.content, null, "Mozilla/5.0")
            } else {
                val video = videos.maxByOrNull { it.height } ?: return@runCatching null
                val audio = info.audioStreams.filter {
                    it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.content.isNotEmpty()
                }.maxByOrNull { it.averageBitrate } ?: return@runCatching null
                VideoStreamInfo(video.content, audio.content, "Mozilla/5.0")
            }
        }.getOrNull()
    }

    fun toggleVideoMode() {
        if (_state.value.videoMode) {
            videoJob?.cancel()
            val position = player.currentPosition.coerceAtLeast(0L)
            val shouldPlay = player.playWhenReady
            _state.value = _state.value.copy(videoMode = false)
            loadTrack(_state.value.currentIndex, autoPlay = shouldPlay, startPositionMs = position)
        } else {
            startVideo(_state.value.activeSong, player.currentPosition.coerceAtLeast(0L), player.playWhenReady)
        }
    }

    fun playVideo(song: SongItemClean) {
        _state.value = _state.value.copy(playlist = listOf(song), currentIndex = 0)
        setFullScreenPlayer(true)
        startVideo(song, 0L, shouldPlay = true)
    }

    private fun startVideo(song: SongItemClean, positionMs: Long, shouldPlay: Boolean) {
        if (song.videoId.isBlank()) return
        playJob?.cancel()
        videoJob?.cancel()
        _state.value = _state.value.copy(videoMode = true, isLoadingStream = true)
        videoJob = viewModelScope.launch {
            try {
                val stream = fetchVideoStream(song.videoId)
                if (stream == null) {
                    _state.value = _state.value.copy(videoMode = false, isLoadingStream = false)
                    Toast.makeText(getApplication(), "Video stream unavailable for this track", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(stream.userAgent)
                    .setAllowCrossProtocolRedirects(true)
                val mediaFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
                val source = stream.dashManifestUrl?.let { manifestUrl ->
                    DashMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(manifestUrl))
                } ?: run {
                    val videoSource = mediaFactory.createMediaSource(MediaItem.fromUri(stream.videoUrl))
                    stream.audioUrl?.let { audioUrl ->
                        MergingMediaSource(videoSource, mediaFactory.createMediaSource(MediaItem.fromUri(audioUrl)))
                    } ?: videoSource
                }
                player.setMediaSource(source)
                player.prepare()
                // Video mode reuses the same ExoPlayer session; attach the saved
                // equalizer profile to its audio output as soon as the source changes.
                updateAudioEffectSession(true)
                if (positionMs > 0L) player.seekTo(positionMs)
                if (shouldPlay) player.play() else player.pause()
                _state.value = _state.value.copy(isLoadingStream = false)
            } catch (_: Exception) {
                _state.value = _state.value.copy(videoMode = false, isLoadingStream = false)
            }
        }
    }

    fun togglePlayerOverlayTab(tab: OverlayTab) {
        val next = if (_state.value.playerOverlayTab == tab) OverlayTab.NONE else tab
        _state.value = _state.value.copy(playerOverlayTab = next)
    }

    fun completeSetupStep(nextStep: Int) {
        if (nextStep >= 11) {
            saveListenerProfile(_state.value.listenerProfile, finishSetup = true)
        } else if (nextStep >= 0) {
            preferences.edit().putInt("setup_step", nextStep).apply()
            _state.value = _state.value.copy(setupStep = nextStep)
        }
    }

    fun selectHomeCategory(category: String) {
        _state.value = _state.value.copy(activeHomeTab = category)
    }

    fun toggleCustomization(key: String, enabled: Boolean) {
        if (UI_CUSTOMIZATION_OPTIONS.none { it.key == key }) return
        preferences.edit().putBoolean("ui_$key", enabled).apply()
        _state.value = _state.value.copy(uiCustomizations = _state.value.uiCustomizations + (key to enabled))
    }

    fun setDownloadDirectory(uri: Uri?) {
        if (uri == null) return
        val persisted = runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        if (persisted.isFailure) return
        preferences.edit().putString("download_directory_uri", uri.toString()).apply()
        _state.value = _state.value.copy(downloadDirectoryUri = uri.toString())
        Toast.makeText(getApplication(), "Download folder saved. Scanning for audio files…", Toast.LENGTH_SHORT).show()
        scanDownloadedFolder(showMessage = true)
    }

    fun scanDownloadedFolder(showMessage: Boolean = true) {
        val app = getApplication<Application>()
        val treeUri = _state.value.downloadDirectoryUri?.let(Uri::parse)
        if (treeUri == null) {
            if (showMessage) Toast.makeText(app, "Choose a music folder in Settings first.", Toast.LENGTH_LONG).show()
            return
        }
        _state.value = _state.value.copy(isScanningDownloads = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = app.contentResolver
                    val treeId = DocumentsContract.getTreeDocumentId(treeUri)
                    val scanned = mutableListOf<SongItemClean>()
                    val visited = mutableSetOf<String>()
                    val projection = arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    )
                    suspend fun scanDirectory(parentId: String) {
                        if (!visited.add(parentId)) return
                        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                            while (cursor.moveToNext()) {
                                val documentId = cursor.getString(idCol) ?: continue
                                val name = cursor.getString(nameCol).orEmpty()
                                val mime = cursor.getString(mimeCol).orEmpty()
                                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                    scanDirectory(documentId)
                                    continue
                                }
                                val extension = name.substringAfterLast('.', "").lowercase()
                                if (!mime.startsWith("audio/") && extension !in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "aiff")) continue
                                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                                val stableHash = MessageDigest.getInstance("SHA-256")
                                    .digest(documentId.toByteArray())
                                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                                val id = "local:" + stableHash
                                val retriever = android.media.MediaMetadataRetriever()
                                val metadata = runCatching {
                                    retriever.setDataSource(app, fileUri)
                                    val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                    val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                                        ?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.', name)
                                    val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                        ?.takeIf { it.isNotBlank() } ?: "Unknown artist"
                                    val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
                                    listOf(title, artist, album, duration.toString())
                                }.getOrNull()
                                runCatching { retriever.release() }
                                if (metadata != null) {
                                    scanned += SongItemClean(
                                        id = id,
                                        videoId = "",
                                        title = metadata[0],
                                        artist = metadata[1],
                                        album = metadata[2],
                                        coverUrl = "",
                                        audioUrl = fileUri.toString(),
                                        durationMs = metadata[3].toLongOrNull() ?: 0L,
                                        codecInfo = (extension.ifBlank { mime.substringAfter('/', "audio").uppercase() }).uppercase() + " • Offline file",
                                        isDownloaded = true
                                    )
                                }
                            }
                        }
                    }
                    scanDirectory(treeId)
                    scanned.distinctBy { it.id }
                }
            }
            if (result.isFailure) {
                Log.w("KiloDownloads", "Could not scan selected audio folder", result.exceptionOrNull())
                _state.value = _state.value.copy(isScanningDownloads = false)
                if (showMessage) withContext(Dispatchers.Main) {
                    Toast.makeText(app, "Could not scan this folder. Check folder access in Settings.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val songs = result.getOrThrow()
            val downloads = runCatching { JSONObject(preferences.getString("downloaded_song_uris", "{}") ?: "{}") }.getOrDefault(JSONObject())
            val currentIds = songs.mapTo(mutableSetOf()) { it.id }
            val staleLocalIds = downloads.keys().asSequence().filter { it.startsWith("local:") && it !in currentIds }.toList()
            staleLocalIds.forEach(downloads::remove)
            songs.forEach { downloads.put(it.id, it.audioUrl) }
            preferences.edit().putString("downloaded_song_uris", downloads.toString()).apply()
            val existingDownloads = _state.value.downloadedSongs.filterNot { it.id.startsWith("local:") }
            persistDownloadedSongs((existingDownloads + songs).distinctBy { it.id })
            _state.value = _state.value.copy(isScanningDownloads = false)
            if (showMessage) {
                val message = if (result.isSuccess) "Found " + songs.size + " audio files in your music folder."
                    else "Couldn’t scan this folder. Check folder access in Settings."
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    fun toggleAutoMenu(menu: String) {
        val current = _state.value.selectedAutoMenus.toMutableList()
        if (current.contains(menu)) {
            if (current.size > 1) current.remove(menu)
        } else current.add(menu)
        _state.value = _state.value.copy(selectedAutoMenus = current)
    }

    fun cycleQuality(type: String) {
        val levels = listOf("Lossless", "High (320kbps)", "Normal (160kbps)")
        when (type) {
            "wifi" -> _state.value = _state.value.copy(
                wifiQuality = levels[(levels.indexOf(_state.value.wifiQuality) + 1) % levels.size]
            )
            "mobile" -> _state.value = _state.value.copy(
                mobileQuality = levels[(levels.indexOf(_state.value.mobileQuality) + 1) % levels.size]
            )
        }
    }

    fun configureLosslessSource(url: String, token: String) {
        val app = getApplication<Application>()
        val cleanedUrl = url.trim().trimEnd('/')
        _state.value = _state.value.copy(losslessSourceStatus = "Checking source…")
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { LosslessSource.testAndSave(app, cleanedUrl, token.trim()) }
            }
            val status = outcome.getOrElse { "Could not connect: ${it.localizedMessage ?: "check the URL"}" }
            _state.value = _state.value.copy(
                losslessSourceUrl = if (outcome.isSuccess) cleanedUrl else _state.value.losslessSourceUrl,
                losslessSourceEnabled = outcome.isSuccess,
                losslessSourceHasToken = LosslessSource.hasToken(app),
                losslessSourceStatus = status
            )
        }
    }

    fun disableLosslessSource() {
        val app = getApplication<Application>()
        LosslessSource.disable(app)
        _state.value = _state.value.copy(
            losslessSourceEnabled = false,
            losslessSourceStatus = "YouTube Music fallback enabled"
        )
    }

    fun toggleDownloadWifiOnly(enabled: Boolean) { _state.value = _state.value.copy(downloadWifiOnly = enabled) }

    fun setSkin(skin: SkinTheme) {
        preferences.edit().putInt("active_skin", skin.ordinal).apply()
        _state.value = _state.value.copy(activeSkin = skin)
    }

    private fun updateAudioEffectSession(open: Boolean) {
        val nextId = if (open) player.audioSessionId else C.AUDIO_SESSION_ID_UNSET
        if (nextId == advertisedAudioEffectSessionId) return
        advertisedAudioEffectSessionId = if (nextId > 0) nextId else C.AUDIO_SESSION_ID_UNSET
        if (nextId > 0) {
            _state.value = _state.value.copy(equalizerSnapshot = equalizerController.attach(nextId))
        }
    }

    fun openEqualizer() {
        val sessionId = player.audioSessionId
        val snapshot = if (sessionId > 0) equalizerController.attach(sessionId) else EqualizerSnapshot()
        _state.value = _state.value.copy(equalizerSnapshot = snapshot)
        setScreen(ScreenNav.EQUALIZER)
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(equalizerSnapshot = equalizerController.setEnabled(enabled))
    }

    fun setEqualizerBand(index: Int, levelMilliBels: Int) {
        _state.value = _state.value.copy(equalizerSnapshot = equalizerController.setBandLevel(index, levelMilliBels))
    }

    fun applyEqualizerPreset(index: Int) {
        _state.value = _state.value.copy(equalizerSnapshot = equalizerController.applyPreset(index))
    }

    fun checkForUpdates() {
        if (_state.value.isCheckingForUpdates) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isCheckingForUpdates = true, updateCheckMessage = "Checking GitHub releases…")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val installedVersion = getApplication<Application>().packageManager
                        .getPackageInfo(getApplication<Application>().packageName, 0).versionName ?: "0"
                    GitHubUpdateChecker.check(installedVersion)
                }
            }
            result.onSuccess { release ->
                _state.value = _state.value.copy(
                    isCheckingForUpdates = false,
                    updateAvailable = release.isUpdateAvailable,
                    updateCheckMessage = if (release.isUpdateAvailable) "${release.title} is available" else "You’re on the latest release (${release.tagName}).",
                    latestReleaseUrl = release.releaseUrl,
                    latestApkUrl = release.apkUrl.orEmpty(),
                    latestReleaseTitle = release.title
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    isCheckingForUpdates = false,
                    updateCheckMessage = "Couldn’t check GitHub: ${error.localizedMessage ?: "connection failed"}"
                )
            }
        }
    }

    fun openReleaseUrl(url: String) {
        if (url.isBlank()) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        }.onFailure {
            Toast.makeText(getApplication<Application>(), "Could not open GitHub release", Toast.LENGTH_SHORT).show()
        }
    }

    fun openSystemEqualizer() {
        val app = getApplication<Application>()
        val sessionId = player.audioSessionId
        if (sessionId <= 0) {
            openEqualizer()
            Toast.makeText(app, "Start playback to attach the equalizer. KILO's equalizer is open.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, app.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { app.startActivity(intent) }
            .onFailure {
                openEqualizer()
                Toast.makeText(app, "No system equalizer panel is available. KILO's equalizer is open instead.", Toast.LENGTH_LONG).show()
            }
    }

    fun playRadioStation(station: RadioStation) {
        playJob?.cancel()
        videoJob?.cancel()
        val song = station.asSong()
        _state.value = _state.value.copy(
            playlist = listOf(song), currentIndex = 0, progress = 0f,
            currentPosMs = 0L, currentDurationMs = 0L,
            isLoadingStream = true, isPlaying = false, videoMode = false,
            lastRadioStation = station
        )
        val app = getApplication<Application>()
        val recent = RadioStationStore.recordPlay(app, station)
        _state.value = _state.value.copy(radioRecentStations = recent)
        viewModelScope.launch {
            try {
                startPlaybackService()
                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent("Kilo/1.5.5 (Android Internet Radio)")
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(30_000)
                val item = MediaItem.Builder()
                    .setMediaId(song.id)
                    .setUri(station.streamUrl)
                    .setMimeType(if (station.streamUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) "application/x-mpegURL" else null)
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(station.name)
                            .setArtist(song.artist)
                            .setAlbumTitle("Internet Radio")
                            .setArtworkUri(Uri.parse(station.favicon).takeIf { station.favicon.isNotBlank() })
                            .build()
                    )
                    .build()
                // Let Media3 choose HLS or progressive playback from the stream URL/MIME type.
                // Forcing ProgressiveMediaSource breaks stations that serve HLS playlists.
                val mediaSource = DefaultMediaSourceFactory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(5))
                    .createMediaSource(item)
                player.setMediaSource(mediaSource)
                player.prepare()
                player.play()
                _state.value = _state.value.copy(isLoadingStream = true, isPlaying = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("KiloRadio", "Could not play radio station " + station.id, error)
                _state.value = _state.value.copy(isLoadingStream = false, isPlaying = false)
                Toast.makeText(app, "Couldn’t play " + station.name + ". Try another station.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun playLastRadioStation() {
        (_state.value.lastRadioStation ?: RadioStationStore.loadLast(getApplication()))?.let(::playRadioStation)
    }

    fun playRandomRadioStation(stations: List<RadioStation>) {
        stations.randomOrNull()?.let(::playRadioStation)
    }

    fun toggleRadioFavorite(station: RadioStation) {
        val current = _state.value.radioFavorites
        val updated = if (current.any { it.id == station.id }) current.filterNot { it.id == station.id } else listOf(station) + current
        RadioStationStore.save(getApplication(), "radio_favorites", updated)
        _state.value = _state.value.copy(radioFavorites = updated)
    }

    fun addCustomRadioStation(name: String, url: String) {
        val station = RadioStationStore.customStation(name, url)
        if (station == null) {
            Toast.makeText(getApplication(), "Enter a valid HTTPS stream URL.", Toast.LENGTH_SHORT).show()
            return
        }
        val updated = listOf(station) + _state.value.customRadioStations
        RadioStationStore.save(getApplication(), "radio_custom_stations", updated)
        _state.value = _state.value.copy(customRadioStations = updated)
    }

    fun removeCustomRadioStation(station: RadioStation) {
        val updated = _state.value.customRadioStations.filterNot { it.id == station.id }
        RadioStationStore.save(getApplication(), "radio_custom_stations", updated)
        _state.value = _state.value.copy(customRadioStations = updated)
    }

    fun setRadioSleepTimer(minutes: Int?) {
        radioSleepJob?.cancel()
        val safeMinutes = minutes?.takeIf { it > 0 }
        _state.value = _state.value.copy(radioSleepTimerMinutes = safeMinutes)
        if (safeMinutes != null) {
            radioSleepJob = viewModelScope.launch {
                delay(safeMinutes * 60_000L)
                player.pause()
                _state.value = _state.value.copy(
                    isPlaying = false, isLoadingStream = false,
                    radioSleepTimerMinutes = null
                )
            }
        }
    }

    fun toggleRadioRecording(station: RadioStation) {
        if (radioRecordingJob?.isActive == true) {
            radioRecordingJob?.cancel()
            radioRecordingJob = null
            _state.value = _state.value.copy(isRadioRecording = false)
            return
        }
        val app = getApplication<Application>()
        val directory = File(app.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Radio")
        radioRecordingJob = viewModelScope.launch(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create radio recordings folder")
                val streamConnection = (URL(station.streamUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "Kilo/1.5.5 (Android Internet Radio)")
                    connect()
                }
                connection = streamConnection
                val responseCode = streamConnection.responseCode
                if (responseCode !in 200..299) throw IOException("Radio server returned HTTP $responseCode")
                val suffix = station.streamUrl.substringBefore('?').substringAfterLast('.', "mp3")
                    .lowercase().takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "mp3"
                val output = File(directory, "radio-" + System.currentTimeMillis() + "." + suffix)
                _state.value = _state.value.copy(isRadioRecording = true)
                streamConnection.inputStream.use { input ->
                    FileOutputStream(output).use { stream ->
                        val buffer = ByteArray(32 * 1024)
                        while (isActive) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            stream.write(buffer, 0, count)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "Radio recording stopped: " + (error.localizedMessage ?: "stream error"), Toast.LENGTH_LONG).show()
                }
            } finally {
                connection?.disconnect()
                _state.value = _state.value.copy(isRadioRecording = false)
            }
        }
    }
    fun setScreen(screen: ScreenNav) {
        val current = _state.value.currentScreen
        if (screen == current) return

        val topLevelScreens = setOf(
            ScreenNav.HOME, ScreenNav.EXPLORE, ScreenNav.LIBRARY, ScreenNav.SEARCH,
            ScreenNav.OFFLINE, ScreenNav.RADIO, ScreenNav.SETTINGS
        )
        if (screen in topLevelScreens) {
            screenBackStack.clear()
            if (screen != ScreenNav.HOME) screenBackStack.addLast(ScreenNav.HOME)
        } else {
            screenBackStack.addLast(current)
        }
        _state.value = _state.value.copy(currentScreen = screen)
    }

    fun handleSystemBack() {
        val current = _state.value
        when {
            current.playerOverlayTab != OverlayTab.NONE -> {
                _state.value = current.copy(playerOverlayTab = OverlayTab.NONE)
            }
            current.isFullScreenPlayerOpen -> setFullScreenPlayer(false)
            current.currentScreen != ScreenNav.HOME -> {
                val previous = screenBackStack.removeLastOrNull() ?: ScreenNav.HOME
                _state.value = current.copy(currentScreen = previous)
            }
        }
    }

    fun toggleFavorite(songId: String) {
        val current = _state.value
        val song = sequenceOf(current.playlist, current.quickPicks, current.searchResults)
            .flatten().firstOrNull { it.id == songId }
            ?: current.homeShelves.asSequence().flatMap { it.songs.asSequence() }.firstOrNull { it.id == songId }
            ?: return
        PlayedSongHistory.recordSignal(song, if (Favorites.isLiked(songId)) "unlike" else "like")
        Favorites.toggle(song)
    }

    // Ultra-fast parallel multi-connection downloader (< 3-5 seconds per track)
    fun toggleDownloadSong(song: SongItemClean) {
        if (song.id in _state.value.downloadsInProgress) return
        val downloads = runCatching { JSONObject(preferences.getString("downloaded_song_uris", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val existingUri = downloads.optString(song.id).takeIf { it.isNotBlank() && it != "null" }
        if (existingUri != null) {
            if (!song.id.startsWith("local:")) runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, Uri.parse(existingUri)) }
            downloads.remove(song.id)
            preferences.edit().putString("downloaded_song_uris", downloads.toString()).apply()
            updateSongDownloadFlag(song.id, false)
            persistDownloadedSongs(_state.value.downloadedSongs.filterNot { it.id == song.id })
            Toast.makeText(getApplication(), if (song.id.startsWith("local:")) "Removed from library; file kept." else "Download removed.", Toast.LENGTH_SHORT).show()
            return
        }
        val folder = _state.value.downloadDirectoryUri?.let(Uri::parse)
        if (folder == null) {
            Toast.makeText(getApplication(), "Choose a download folder in Settings first.", Toast.LENGTH_LONG).show()
            return
        }
        if (song.videoId.isBlank()) return

        _state.value = _state.value.copy(downloadsInProgress = _state.value.downloadsInProgress + (song.id to DownloadTaskState(song, 0.02f)))
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var info = FastStream.get(song.videoId, { id -> YtRepo.fetchStream(id) }, getApplication<Application>())
                        ?: error("Could not resolve audio stream")
                    val isM4a = info.codec.contains("m4a", ignoreCase = true) || info.codec.contains("aac", ignoreCase = true)
                    val mime = if (isM4a) "audio/mp4" else "audio/webm"
                    val ext = if (isM4a) "m4a" else "webm"

                    val resolver = getApplication<Application>().contentResolver
                    val treeId = DocumentsContract.getTreeDocumentId(folder)
                    val parent = DocumentsContract.buildDocumentUriUsingTree(folder, treeId)
                    val safeTitle = song.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
                    val safeArtist = song.artist.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(50)
                    val target = DocumentsContract.createDocument(resolver, parent, mime, "$safeTitle - $safeArtist.$ext")
                        ?: error("Could not create file")

                    fun copyAudio(source: StreamInfo) {
                        val totalBytes = ChunkedDownloader.contentLength(source.url, source.userAgent)
                        if (totalBytes in 1..60_000_000L) {
                            val bytes = ChunkedDownloader.download(source.url, source.userAgent, totalBytes) { p ->
                                updateDownloadProgress(song.id, p)
                            }
                            resolver.openOutputStream(target, "w")?.use { it.write(bytes) }
                                ?: error("Could not write file")
                            return
                        }

                        val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 8000
                            readTimeout = 30000
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", source.userAgent)
                            setRequestProperty("Accept-Encoding", "identity")
                            setRequestProperty("Referer", "https://www.youtube.com/")
                        }
                        try {
                            val status = connection.responseCode
                            if (status !in 200..299) throw IOException("Audio request HTTP $status")
                            val length = connection.contentLengthLong
                            var copied = 0L
                            connection.inputStream.use { input ->
                                resolver.openOutputStream(target, "w")?.buffered(65536)?.use { output ->
                                    val buffer = ByteArray(128 * 1024)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                        copied += count
                                        if (length > 0) updateDownloadProgress(song.id, (copied.toFloat() / length).coerceIn(0f, 1f))
                                    }
                                } ?: error("Could not write file")
                            }
                        } finally {
                            connection.disconnect()
                        }
                    }

                    var attempt = 0
                    while (true) {
                        try {
                            copyAudio(info)
                            break
                        } catch (error: Exception) {
                            val wasForbidden = generateSequence(error as Throwable?) { it.cause }
                                .any { it.message?.contains("403") == true }
                            if (attempt++ > 0 || !wasForbidden) throw error
                            FastStream.invalidate(song.videoId)
                            info = FastStream.get(song.videoId, { id -> YtRepo.fetchStream(id) }, getApplication<Application>())
                                ?: throw error
                        }
                    }
                    target to info.codec
                }
            }
            val saved = result.getOrNull()
            if (saved != null) {
                downloads.put(song.id, saved.first.toString())
                preferences.edit().putString("downloaded_song_uris", downloads.toString()).apply()
                val savedSong = song.copy(isDownloaded = true, codecInfo = saved.second)
                updateSongDownloadFlag(song.id, true)
                persistDownloadedSongs(_state.value.downloadedSongs.filterNot { it.id == song.id } + savedSong)
                _state.value = _state.value.copy(downloadsInProgress = _state.value.downloadsInProgress - song.id)
                Toast.makeText(getApplication(), "Saved ${song.title} for offline play!", Toast.LENGTH_SHORT).show()
            } else {
                _state.value = _state.value.copy(downloadsInProgress = _state.value.downloadsInProgress - song.id)
                Toast.makeText(getApplication(), "Download failed: ${result.exceptionOrNull()?.localizedMessage ?: "Network error"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateDownloadProgress(songId: String, progress: Float) {
        val tasks = _state.value.downloadsInProgress
        val task = tasks[songId] ?: return
        _state.value = _state.value.copy(downloadsInProgress = tasks + (songId to task.copy(progress = progress)))
    }

    private fun downloadedUriForSong(songId: String): Uri? {
        val encoded = runCatching { JSONObject(preferences.getString("downloaded_song_uris", "{}") ?: "{}") }.getOrNull()
            ?.optString(songId)?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return Uri.parse(encoded)
    }

    private fun loadDownloadedSongs(): List<SongItemClean> = runCatching {
        val array = JSONArray(preferences.getString("downloaded_songs", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val songId = item.optString("id")
            if (songId.isBlank() || downloadedUriForSong(songId) == null) return@mapNotNull null
            SongItemClean(
                id = songId,
                videoId = item.optString("videoId", songId),
                title = item.optString("title", "Downloaded track"),
                artist = item.optString("artist", "Unknown artist"),
                album = item.optString("album"),
                coverUrl = item.optString("coverUrl"),
                durationMs = item.optLong("durationMs", 215000L),
                codecInfo = item.optString("codecInfo", "AAC • 256 kbps • Offline"),
                isDownloaded = true
            )
        }
    }.getOrDefault(emptyList())

    private fun persistDownloadedSongs(songs: List<SongItemClean>) {
        val array = JSONArray()
        songs.forEach { song ->
            array.put(JSONObject().apply {
                put("id", song.id)
                put("videoId", song.videoId)
                put("title", song.title)
                put("artist", song.artist)
                put("album", song.album)
                put("coverUrl", song.coverUrl)
                put("durationMs", song.durationMs)
                put("codecInfo", song.codecInfo)
            })
        }
        preferences.edit().putString("downloaded_songs", array.toString()).apply()
        _state.value = _state.value.copy(downloadedSongs = songs)
    }

    private fun updateSongDownloadFlag(songId: String, downloaded: Boolean) {
        fun List<SongItemClean>.updated() = map { if (it.id == songId) it.copy(isDownloaded = downloaded) else it }
        _state.value = _state.value.copy(
            playlist = _state.value.playlist.updated(),
            quickPicks = _state.value.quickPicks.updated(),
            searchResults = _state.value.searchResults.updated(),
            homeShelves = _state.value.homeShelves.map { it.copy(songs = it.songs.updated()) },
            savedPlaylists = _state.value.savedPlaylists.map { it.copy(songs = it.songs.updated()) }
        )
    }

    fun setAutoLayoutMode(mode: String) {
        if (mode !in AUTO_LAYOUT_MODES) return
        preferences.edit().putString("auto_layout_mode", mode).apply()
        _state.value = _state.value.copy(autoLayoutMode = mode)
    }

    fun setAutoPlayOnCarConnect(enabled: Boolean) {
        preferences.edit().putBoolean("auto_play_car_connect", enabled).apply()
        _state.value = _state.value.copy(autoPlayOnCarConnect = enabled)
    }

    fun setPauseForCalls(enabled: Boolean) {
        preferences.edit().putBoolean("pause_for_calls", enabled).apply()
        _state.value = _state.value.copy(pauseForCalls = enabled)
        PlayerHolder.setCallAudioFocus(enabled)
    }

    fun setMatureContentFilter(enabled: Boolean) {
        preferences.edit().putBoolean("filter_mature_content", enabled).apply()
        _state.value = _state.value.copy(filterMatureContent = enabled)
        loadHome()
    }

    fun surpriseMe() {
        viewModelScope.launch {
            val (seen, recent) = withContext(Dispatchers.IO) {
                PlayedSongHistory.ids() to SessionStore.recentSongs().map { it.id }.toSet()
            }
            val all = _state.value.homeShelves.flatMap { it.songs }.distinctBy { it.id }
            var unseen = all.filter {
                it.videoId.isNotBlank() && it.id !in seen && it.id !in recent &&
                    it.id !in _state.value.favoriteIds && it.id !in _state.value.notRecommendedIds &&
                    (!_state.value.filterMatureContent || !it.isExplicit)
            }.shuffled()
            if (unseen.isEmpty()) {
                val genres = listOf("indie discoveries", "new alternative songs", "underground pop", "new electronic music", "rising artists")
                val query = genres[(System.currentTimeMillis() / 60_000L % genres.size).toInt()]
                val fetched = withContext(Dispatchers.IO) { YtRepo.searchSongs(query) }
                unseen = fetched.filter {
                    it.videoId.isNotBlank() && it.id !in seen && it.id !in recent &&
                        it.id !in _state.value.favoriteIds && it.id !in _state.value.notRecommendedIds &&
                        (!_state.value.filterMatureContent || !it.isExplicit)
                }.distinctBy { it.id }.shuffled()
            }
            if (unseen.isEmpty()) {
                Toast.makeText(getApplication(), "No fresh picks right now. Try again shortly.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            _state.value = _state.value.copy(playlist = unseen, currentIndex = 0)
            loadTrack(0, autoPlay = true)
        }
    }

    fun setPlayerStyle(styleId: String) {
        if (PLAYER_STYLE_PRESETS.none { it.id == styleId }) return
        preferences.edit().putString("player_style_id", styleId).apply()
        _state.value = _state.value.copy(selectedPlayerStyleId = styleId)
    }

    private fun loadAccounts(): List<UserProfileAccount> = runCatching {
        val json = JSONArray(preferences.getString("profile_accounts", "[]"))
        (0 until json.length()).mapNotNull { index ->
            val item = json.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            UserProfileAccount(
                id = id,
                name = item.optString("name", "Music Profile"),
                profileImage = item.optString("profileImage"),
                cookie = item.optString("cookie"),
                favoriteGenre = item.optString("favoriteGenre", "Discover")
            )
        }.ifEmpty { listOf(UserProfileAccount("1", "Main Profile", "", "", "Discover")) }
    }.getOrDefault(listOf(UserProfileAccount("1", "Main Profile", "", "", "Discover")))

    private fun persistAccounts(accounts: List<UserProfileAccount>, activeId: String) {
        val json = JSONArray()
        accounts.forEach { account ->
            json.put(JSONObject()
                .put("id", account.id)
                .put("name", account.name)
                .put("profileImage", account.profileImage)
                .put("cookie", account.cookie)
                .put("favoriteGenre", account.favoriteGenre))
        }
        preferences.edit()
            .putString("profile_accounts", json.toString())
            .putString("active_account_id", activeId)
            .apply()
    }

    private fun loadSavedPlaylists(): List<SavedPlaylist> = runCatching {
        val array = JSONArray(preferences.getString("saved_playlists", "[]"))
        (0 until array.length()).mapNotNull playlistLoop@ { index ->
            val item = array.optJSONObject(index) ?: return@playlistLoop null
            val songsJson = item.optJSONArray("songs") ?: JSONArray()
            val songs = (0 until songsJson.length()).mapNotNull songLoop@ { songIndex ->
                val json = songsJson.optJSONObject(songIndex) ?: return@songLoop null
                SongItemClean(
                    id = json.optString("id"), videoId = json.optString("videoId"),
                    title = json.optString("title"), artist = json.optString("artist"),
                    album = json.optString("album"), coverUrl = json.optString("coverUrl"),
                    durationMs = json.optLong("durationMs", 215000L),
                    isDownloaded = json.optBoolean("isDownloaded", false)
                )
            }
            SavedPlaylist(item.optString("id"), item.optString("name"), songs)
        }
    }.getOrDefault(emptyList())

    private fun persistSavedPlaylists(playlists: List<SavedPlaylist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            val songs = JSONArray()
            playlist.songs.forEach { song ->
                songs.put(JSONObject().apply {
                    put("id", song.id); put("videoId", song.videoId); put("title", song.title)
                    put("artist", song.artist); put("album", song.album); put("coverUrl", song.coverUrl)
                    put("durationMs", song.durationMs); put("isDownloaded", song.isDownloaded)
                })
            }
            array.put(JSONObject().put("id", playlist.id).put("name", playlist.name).put("songs", songs))
        }
        preferences.edit().putString("saved_playlists", array.toString()).apply()
        _state.value = _state.value.copy(savedPlaylists = playlists)
    }

    fun addSongToPlaylist(playlistId: String, song: SongItemClean) {
        val updated = _state.value.savedPlaylists.map { playlist ->
            if (playlist.id == playlistId && playlist.songs.none { it.id == song.id }) playlist.copy(songs = playlist.songs + song)
            else playlist
        }
        persistSavedPlaylists(updated)
    }

    fun createPlaylist(name: String, song: SongItemClean) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        val playlist = SavedPlaylist(System.currentTimeMillis().toString(), cleanName, listOf(song))
        persistSavedPlaylists(_state.value.savedPlaylists + playlist)
    }

    fun setSongRecommendation(songId: String, recommend: Boolean) {
        val ids = _state.value.notRecommendedIds.toMutableSet()
        if (recommend) ids.remove(songId) else ids.add(songId)
        preferences.edit().putStringSet("not_recommended_ids", ids.toSet()).apply()
        _state.value = _state.value.copy(notRecommendedIds = ids)
    }

    override fun onCleared() {
        Favorites.removeListener(favoriteListener)
        radioSleepJob?.cancel()
        radioRecordingJob?.cancel()
        player.removeListener(playbackListener)
        updateAudioEffectSession(false)
        equalizerController.release()
    }
}

// ───────────────────────── ACTIVITY & DISTINCTIVE "VYBE" UI ─────────────────────────

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                VybeRootApp(viewModel)
            }
        }
        handleAssistantVoiceIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAssistantVoiceIntent(intent)
    }

    private fun handleAssistantVoiceIntent(intent: Intent?) {
        val query = intent?.getStringExtra("query")?.trim().orEmpty()
        if (query.isNotEmpty()) {
            window.decorView.post { viewModel.playFromAssistantQuery(query) }
        }
    }
}

@Composable
fun VybeRootApp(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    var showLoginPopup by remember { mutableStateOf(false) }

    LaunchedEffect(state.isSetupComplete, state.isLoggedIn) {
        if (state.isSetupComplete && !state.isLoggedIn) {
            showLoginPopup = true
        }
    }

    if (state.showWebLoginDialog) {
        BitChordWebViewLoginDialog(
            onLoginSuccess = { cookies -> vm.onWebLoginSuccess(cookies) },
            onDismiss = { vm.closeWebLogin() }
        )
    }

    if (state.showAccountManagerDialog) {
        AccountManagerDialog(
            accounts = state.accountsList,
            activeId = state.activeAccountId,
            onAddAccount = { vm.openWebLogin() },
            onSelectAccount = { vm.switchAccount(it) },
            onDismiss = { vm.toggleAccountManager(false) }
        )
    }

    if (state.isSetupComplete && state.showPersonalizationWizard) {
        PersonalizationWizardDialog(vm)
    }

    if (!state.isSetupComplete) {
        BitChordSetupWizard(vm)
    } else {
        BackHandler(
            enabled = state.isFullScreenPlayerOpen ||
                state.playerOverlayTab != OverlayTab.NONE ||
                state.currentScreen != ScreenNav.HOME
        ) {
            vm.handleSystemBack()
        }
        Box(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalUiCustomizations provides state.uiCustomizations,
                LocalPrimarySkin provides state.activeSkin
            ) {
                VybeMainScaffold(vm)

                AnimatedVisibility(
                    visible = state.isFullScreenPlayerOpen,
                    enter = if (state.uiCustomizations["reduceMotion"] == true || state.uiCustomizations["smoothMotion"] == false) fadeIn() else slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = if (state.uiCustomizations["reduceMotion"] == true || state.uiCustomizations["smoothMotion"] == false) fadeOut() else slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    BitchordFullScreenPlayer(state = state, vm = vm)
                }
            }
        }

        if (showLoginPopup && !state.isLoggedIn) {
            AlertDialog(
                onDismissRequest = { showLoginPopup = false },
                title = { Text("Sync Your YouTube Music", fontWeight = FontWeight.Bold) },
                text = { Text("Sign in to load music discovery and online playlists. Without signing in, only songs saved in your Offline Vault are available.") },
                confirmButton = {
                    TextButton(onClick = {
                        showLoginPopup = false
                        vm.openWebLogin()
                    }) {
                        Text("Sign in", color = state.activeSkin.highlight, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLoginPopup = false }) {
                        Text("Offline only", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF14131D),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(0.8f)
            )
        }
    }
}

@Composable
private fun PersonalizationWizardDialog(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val profile = state.listenerProfile
    var step by remember { mutableIntStateOf(0) }
    var artistQuery by remember { mutableStateOf("") }
    val regionalPicks = remember { regionalArtistPicks(Locale.getDefault().country) }
    LaunchedEffect(artistQuery) {
        delay(350)
        vm.searchProfileArtists(artistQuery)
    }

    Dialog(onDismissRequest = vm::skipPersonalizationWizard) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 700.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF15131D)
        ) {
            Column(Modifier.fillMaxWidth().padding(22.dp)) {
                Text("Let's personalize KILO", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                Text("Optional · Step ${step + 1} of 4", color = state.activeSkin.highlight, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    when (step) {
                        0 -> {
                            Text("Choose genres you enjoy", color = Color.White, fontWeight = FontWeight.Bold)
                            ProfileChoiceGrid(PERSONALIZATION_GENRES, profile.favoriteGenres, vm::toggleProfileGenre)
                        }
                        1 -> {
                            ProfileArtistPicker(vm, state, artistQuery) { artistQuery = it }
                        }
                        2 -> {
                            Text("Pick moods for different moments", color = Color.White, fontWeight = FontWeight.Bold)
                            ProfileChoiceGrid(PERSONALIZATION_MOODS, profile.favoriteMoods, vm::toggleProfileMood)
                        }
                        else -> {
                            Text("Tune your recommendations", color = Color.White, fontWeight = FontWeight.Bold)
                            ProfileSingleChoice("Discovery", listOf("Conservative", "Balanced", "Explorer", "Adventurer"), profile.discoveryLevel) { vm.setProfileChoices(discovery = it) }
                            ProfileSingleChoice("Audio preference", listOf("Balanced", "Bass Lover", "Audiophile", "Vocal Focus", "Podcast", "Custom"), profile.audioPreference) { vm.setProfileChoices(audio = it) }
                            ProfileSingleChoice("Android Auto", listOf("Often", "Sometimes", "Never"), profile.androidAutoUsage) { vm.setProfileChoices(androidAuto = it) }
                            Text("KILO keeps this profile on your device. It shapes Home, recommendations, radio and Android Auto.", color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::skipPersonalizationWizard) { Text("Skip", color = Color.White.copy(alpha = 0.7f)) }
                    Spacer(Modifier.weight(1f))
                    if (step > 0) OutlinedButton(onClick = { step-- }) { Text("Back") }
                    Button(
                        onClick = { if (step < 3) step++ else vm.saveListenerProfile(profile) },
                        colors = ButtonDefaults.buttonColors(containerColor = state.activeSkin.highlight)
                    ) { Text(if (step < 3) "Continue" else "Finish", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun ProfileArtistPicker(vm: MainViewModel, state: AppStateModel, artistQuery: String, onQueryChange: (String) -> Unit) {
    val profile = state.listenerProfile
    val region = remember(state.musicRegionCode) {
        regionalArtistPicks(if (state.musicRegionCode == "AUTO") Locale.getDefault().country else state.musicRegionCode)
    }
    LaunchedEffect(artistQuery) {
        delay(350)
        vm.searchProfileArtists(artistQuery)
    }
    Column {
        Text("Follow artists", color = Color.White, fontWeight = FontWeight.Bold)
        Text("Pick artists from popular lists or search YouTube Music. Your picks shape recommendations.", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
        OutlinedTextField(
            value = artistQuery,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            singleLine = true,
            label = { Text("Search artists") }
        )
        if (artistQuery.isBlank()) {
            Text("Popular worldwide", color = state.activeSkin.highlight, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            GLOBAL_ARTIST_PICKS.forEach { name -> ProfileArtistPick(name, name in profile.followedArtists, state.activeSkin.highlight, vm::toggleFollowedArtist) }
            Text("Popular in ${region.label}", color = state.activeSkin.highlight, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            region.artists.forEach { name -> ProfileArtistPick(name, name in profile.followedArtists, state.activeSkin.highlight, vm::toggleFollowedArtist) }
        } else {
            val curatedMatches = (GLOBAL_ARTIST_PICKS + region.artists)
                .filter { it.contains(artistQuery.trim(), ignoreCase = true) }
            val searchMatches = state.profileArtistSuggestions.map { it.name }
            (curatedMatches + searchMatches).distinctBy { it.lowercase(Locale.ROOT) }.take(24).forEach { name ->
                ProfileArtistPick(name, name in profile.followedArtists, state.activeSkin.highlight, vm::toggleFollowedArtist)
            }
        }
        if (profile.followedArtists.isNotEmpty()) {
            Text("Following: ${profile.followedArtists.joinToString()}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProfileArtistPick(name: String, selected: Boolean, accent: Color, onToggle: (String) -> Unit) {
    TextButton(onClick = { onToggle(name) }, modifier = Modifier.fillMaxWidth()) {
        Text(if (selected) "✓ $name" else "+ $name", color = if (selected) accent else Color.White)
    }
}

@Composable
private fun ProfileChoiceGrid(options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { value ->
                    FilterChip(
                        selected = value in selected,
                        onClick = { onToggle(value) },
                        label = { Text(value, fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ProfileSingleChoice(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { value ->
                    FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(value, fontSize = 10.sp) }, modifier = Modifier.weight(1f))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// Unique VYBE Pulse Emblem (Replacing generic wave clone)
@Composable
fun BitchordWaveLogo(modifier: Modifier = Modifier.size(36.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val barHeights = listOf(0.38f, 0.72f, 0.96f, 0.64f, 0.42f)
        val barWidth = w / 9f
        barHeights.forEachIndexed { idx, frac ->
            val x = w * (0.14f + idx * 0.18f)
            val barH = h * frac
            val top = (h - barH) / 2f
            val path = Path().apply {
                moveTo(x, top)
                lineTo(x, top + barH)
            }
            drawPath(
                path = path,
                brush = Brush.verticalGradient(listOf(Color(0xFFFF8A65), Color(0xFFFF5252), Color(0xFFAB47BC))),
                style = Stroke(width = barWidth, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun VybeMainScaffold(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val backdropBrush = if (state.uiCustomizations["gradientBackdrop"] != false) primaryBackdrop(state.activeSkin)
    else Brush.verticalGradient(listOf(Color(0xFF0A0B10), Color(0xFF0A0B10)))

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF090A0F)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backdropBrush)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(bottom = 148.dp)
            ) {
                when (state.currentScreen) {
                    ScreenNav.HOME -> FreshFriendlyHomeScreen(vm)
                    ScreenNav.EXPLORE -> ExploreScreen(vm)
                    ScreenNav.LIBRARY -> LibraryScreen(vm)
                    ScreenNav.SEARCH -> SearchScreen(vm)
                    ScreenNav.OFFLINE -> OfflineMusicScreen(vm)
                    ScreenNav.RADIO -> RadioScreen(vm)
                    ScreenNav.SETTINGS -> BitChordSettingsScreen(vm)
                    ScreenNav.APP_APPEARANCE -> AppAppearanceScreen(vm)
                    ScreenNav.ANDROID_AUTO_SETTINGS -> AndroidAutoSettingsScreen(vm)
                    ScreenNav.PLAYER_STYLES -> PlayerStylesScreen(vm)
                    ScreenNav.EQUALIZER -> EqualizerScreen(vm)
                    ScreenNav.V2_ROADMAP -> V2RoadmapScreen(vm)
                    ScreenNav.ARTIST_DETAIL -> ArtistDetailScreen(vm)
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.uiCustomizations["showMiniPlayer"] != false) {
                    BitchordFloatingMiniPlayer(
                        state = state,
                        vm = vm,
                        onExpand = { vm.setFullScreenPlayer(true) }
                    )
                }

                BitChordBottomNavPill(state = state, vm = vm)
            }
        }
    }
}

@Composable
private fun SignInRequiredContent(vm: MainViewModel, skin: SkinTheme) {
    val accent = skin.highlight
    Box(
        Modifier.fillMaxSize().background(primaryBackdrop(skin)).padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().clip(primaryCardShape(skin)).background(primaryPanelColor(skin))
                .border(1.dp, accent.copy(alpha = 0.32f), primaryCardShape(skin)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(Icons.Default.Public, contentDescription = null, tint = accent, modifier = Modifier.size(48.dp))
            Text("Sign in to discover music", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Text(
                "Connect YouTube Music to load regional recommendations, artists, albums and playlists. Your Offline Vault stays available for downloaded songs.",
                color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
            )
            Button(
                onClick = { vm.openWebLogin() },
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign in to YouTube Music", color = Color.Black, fontWeight = FontWeight.ExtraBold)
            }
            TextButton(onClick = { vm.setScreen(ScreenNav.OFFLINE) }) {
                Text("Open Offline Vault", color = accent, fontWeight = FontWeight.Bold)
            }
        }
    }
}
@Composable
fun FreshFriendlyHomeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val skin = state.activeSkin
    val accent = skin.highlight
    val shelves = state.homeShelves.filterIndexed { index, _ ->
        if (index == 0) state.uiCustomizations["showQuickPicks"] != false
        else state.uiCustomizations["showCuratedShelves"] != false
    }.map { shelf ->
        shelf.copy(songs = shelf.songs.filterNot { it.id in state.notRecommendedIds })
    }.filter { it.songs.isNotEmpty() }
    val songs = shelves.flatMap { it.songs }.distinctBy { it.id }
    val featured = if (state.isPlaying) state.activeSong else songs.firstOrNull()
    val family = skin.layoutFamily

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(primaryBackdrop(skin)),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        when (family) {
                            0 -> "KILO · GLASS EDITION"
                            1 -> "GOOD ${if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 17) "AFTERNOON" else "EVENING"}"
                            2 -> "VIBES FOR YOU"
                            3 -> "LISTEN NOW"
                            else -> "KILO WAVE"
                        },
                        color = accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.7.sp
                    )
                    Text(
                        when (family) {
                            0 -> "A softer sound space"
                            1 -> "Hey, ${state.displayName.ifBlank { "Listener" }}"
                            2 -> "Find your next vibe"
                            3 -> "Made for your moments"
                            else -> "Discover your sound"
                        },
                        color = Color.White, fontSize = if (family == 3) 30.sp else 25.sp, fontWeight = FontWeight.ExtraBold
                    )
                }
                IconButton(
                    onClick = { vm.setScreen(ScreenNav.SEARCH) },
                    modifier = Modifier.size(46.dp).clip(CircleShape)
                        .background(if (family == 0) Color.White.copy(alpha = 0.10f) else primaryPanelColor(skin))
                        .then(if (family == 0) Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape) else Modifier)
                ) { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White) }
            }
        }

        item(key = "personalization_shortcuts") {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
                Text("MOODS", color = accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 20.dp, bottom = 3.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PERSONALIZATION_MOODS) { mood ->
                        val selected = mood in state.listenerProfile.favoriteMoods || state.selectedMood == mood
                        FilterChip(
                            selected = selected,
                            onClick = { vm.toggleProfileMood(mood); vm.setDiscoveryMood(mood) },
                            label = { Text(mood, fontSize = 11.sp) }
                        )
                    }
                }
                Text("GENRES", color = accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 20.dp, top = 7.dp, bottom = 3.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PERSONALIZATION_GENRES) { genre ->
                        val selected = genre in state.listenerProfile.favoriteGenres || state.selectedMood == genre
                        FilterChip(
                            selected = selected,
                            onClick = { vm.toggleProfileGenre(genre); vm.setDiscoveryMood(genre) },
                            label = { Text(genre, fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        if (family == 0 || family == 3 || skin.ordinal == 11) {
            if (featured != null) item {
                PrimaryStyleHeroCard(
                    song = featured,
                    skin = skin,
                    eyebrow = when (skin.ordinal) { 0, 4, 5 -> "YOUR DAILY SOUNDTRACK"; 11 -> "NIGHT DRIVE · CONTINUE LISTENING"; else -> "PICK UP WHERE YOU LEFT OFF" },
                    onClick = {
                        if (state.isPlaying) vm.setFullScreenPlayer(true)
                        else vm.playSongFromShelf(featured, songs)
                    },
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
        }

        if (shelves.isNotEmpty()) item {
            val homeTabs = listOf("All") + shelves.map { it.title }.distinct()
            LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                items(homeTabs) { label ->
                    val selected = state.activeHomeTab == label
                    val chipShape = if (family == 2) RoundedCornerShape(50) else primaryCardShape(skin)
                    Box(
                        Modifier.clip(chipShape).background(if (selected) accent.copy(alpha = 0.24f) else primaryPanelColor(skin))
                            .border(1.dp, if (selected) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.07f), chipShape)
                            .clickable { vm.selectHomeCategory(label) }.padding(horizontal = 15.dp, vertical = 9.dp)
                    ) { Text(label, color = Color.White, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1) }
                }
            }
        }

        if (family == 1) {
            val quickColumns = if (skin.ordinal == 8) 3 else 2
            val quickSongs = (shelves.firstOrNull()?.songs.orEmpty() + songs).distinctBy { it.id }.take(quickColumns * 3)
            item {
                Text("Your quick picks", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 10.dp))
            }
            quickSongs.chunked(quickColumns).forEachIndexed { rowIndex, rowSongs ->
                item(key = "quick_grid_$rowIndex") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        rowSongs.forEach { song ->
                            PrimaryQuickTile(song, skin, Modifier.weight(1f)) { vm.playSongFromShelf(song, songs) }
                        }
                        if (rowSongs.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        if (family == 4 && featured != null) item {
            PrimaryWaveCard(featured, skin, onClick = {
                if (state.isPlaying) vm.setFullScreenPlayer(true) else vm.playSongFromShelf(featured, songs)
            }, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        }

        if (shelves.isEmpty()) item {
            Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.Center) {
                Text(if (state.isSearching) "Finding music for you…" else "Your music feed will appear here", color = Color.White.copy(alpha = 0.65f))
            }
        }

        shelves.forEachIndexed { index, shelf ->
            if (state.activeHomeTab == "All" || state.activeHomeTab == shelf.title) {
            val visibleSongs = shelf.songs.take(if (family == 1) 6 else 10)
            item(key = "primary_shelf_title_$index") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        if (family == 3) Text("CURATED FOR YOU", color = accent, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                        Text(shelf.title, color = Color.White, fontSize = if (family == 3) 21.sp else 18.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Text("Play", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { visibleSongs.firstOrNull()?.let { vm.playSongFromShelf(it, visibleSongs) } }.padding(6.dp))
                }
            }
            if (family == 4 || shelf.isList || skin.ordinal == 7 || skin.ordinal == 10 || skin.ordinal == 12) {
                itemsIndexed(visibleSongs, key = { _, song -> "wave_${index}_${song.id}" }) { _, song ->
                    BitchordSongRowItem(song, state.activeSong.id == song.id, { vm.playSongFromShelf(song, visibleSongs) }, vm)
                }
            } else if (family == 1 || skin.ordinal == 13) {
                val columns = if (skin.ordinal == 8) 3 else 2
                visibleSongs.chunked(columns).forEachIndexed { rowIndex, rowSongs ->
                    item(key = "tile_shelf_${index}_$rowIndex") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            rowSongs.forEach { song -> PrimaryQuickTile(song, skin, Modifier.weight(1f)) { vm.playSongFromShelf(song, visibleSongs) } }
                            if (rowSongs.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                item(key = "shelf_row_$index") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(if (family == 0) 13.dp else 11.dp)) {
                        items(visibleSongs, key = { "art_${index}_${it.id}" }) { song ->
                            PrimaryArtworkTile(song, skin, if (skin.ordinal == 9) 3 else family) { vm.playSongFromShelf(song, visibleSongs) }
                        }
                    }
                }
            }
            }
        }

        item(key = "surprise_me_primary") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 24.dp)
                    .clip(primaryCardShape(skin)).background(primaryPanelColor(skin))
                    .border(1.dp, accent.copy(alpha = 0.34f), primaryCardShape(skin))
                    .clickable { vm.surpriseMe() }.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
                Column(Modifier.weight(1f)) {
                    Text("Surprise me", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Text("One new song, picked for you", color = Color.White.copy(alpha = 0.68f), fontSize = 12.sp)
                }
                Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = accent)
            }
        }
    }
}

@Composable
private fun PrimaryStyleHeroCard(song: SongItemClean, skin: SkinTheme, eyebrow: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val heroHeights = listOf(280, 276, 292, 355, 264, 300, 260, 276, 292, 310, 340, 320, 355, 334, 282)
    Box(modifier.fillMaxWidth().height(heroHeights[skin.ordinal].dp).clip(primaryCardShape(skin)).clickable { onClick() }) {
        AsyncImage(song.coverUrl, contentDescription = song.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.88f)))))
        if (skin.layoutFamily == 0) {
            Box(Modifier.fillMaxSize().padding(10.dp).clip(primaryCardShape(skin)).background(Color.White.copy(alpha = 0.06f)).border(1.dp, Color.White.copy(alpha = 0.19f), primaryCardShape(skin)))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Text(eyebrow, color = Color.White.copy(alpha = 0.76f), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(5.dp))
            Text(song.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(18.dp).size(48.dp).clip(CircleShape).background(skin.highlight), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${song.title}", tint = Color.Black, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun PrimaryQuickTile(song: SongItemClean, skin: SkinTheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(66.dp).clip(primaryCardShape(skin)).background(primaryPanelColor(skin)).clickable { onClick() }
            .then(if (skin.layoutFamily == 0) Modifier.border(1.dp, Color.White.copy(alpha = 0.09f), primaryCardShape(skin)) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(song.coverUrl, contentDescription = song.title, modifier = Modifier.width(62.dp).fillMaxHeight().clip(primaryCardShape(skin)), contentScale = ContentScale.Crop)
        Text(song.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 9.dp).weight(1f))
    }
}

@Composable
private fun PrimaryArtworkTile(song: SongItemClean, skin: SkinTheme, family: Int, onClick: () -> Unit) {
    val widths = listOf(168, 148, 164, 204, 142, 182, 164, 136, 158, 152, 198, 180, 206, 188, 156)
    val width = widths[skin.ordinal].dp
    Column(Modifier.width(width).clickable { onClick() }) {
        AsyncImage(song.coverUrl, contentDescription = song.title, modifier = Modifier.fillMaxWidth().height(if (family == 3) 204.dp else 154.dp).clip(primaryCardShape(skin)), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(8.dp))
        Text(song.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(song.artist, color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PrimaryWaveCard(song: SongItemClean, skin: SkinTheme, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(primaryCardShape(skin)).background(primaryPanelColor(skin)).clickable { onClick() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(song.coverUrl, contentDescription = song.title, modifier = Modifier.size(76.dp).clip(primaryCardShape(skin)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text("NOW IN THE MIX", color = skin.highlight, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = Color.White.copy(alpha = 0.66f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Canvas(Modifier.fillMaxWidth().height(22.dp).padding(top = 7.dp)) {
                val gap = size.width / 30f
                repeat(28) { index ->
                    val height = size.height * (0.28f + ((index * 37 % 63) / 100f))
                    drawLine(skin.highlight.copy(alpha = 0.85f), androidx.compose.ui.geometry.Offset(index * gap, (size.height - height) / 2), androidx.compose.ui.geometry.Offset(index * gap, (size.height + height) / 2), gap * 0.42f, cap = StrokeCap.Round)
                }
            }
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = skin.highlight, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun LegacyFreshFriendlyHomeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val accent = state.activeSkin.highlight

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF171326), Color(0xFF0D0E14), Color(0xFF08090D)))),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BitchordWaveLogo(modifier = Modifier.size(34.dp))
                        Column {
                            Text(
                                text = "VYBE STUDIO",
                                color = accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 2.2.sp
                            )
                            if (state.uiCustomizations["showGreeting"] != false) {
                                Text(
                                    text = "Hey, ${state.displayName.ifBlank { "Listener" }}",
                                    color = Color.White,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { vm.setScreen(ScreenNav.SEARCH) },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.07f))
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                        }
                        IconButton(
                            onClick = { vm.toggleAccountManager(true) },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(accent.copy(alpha = 0.18f))
                                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        ) {
                            Icon(Icons.Filled.AccountCircle, contentDescription = "Accounts", tint = accent, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }

        item {
            val featuredShelf = state.homeShelves.firstOrNull { shelf -> shelf.songs.any { it.id !in state.notRecommendedIds } }
            val featuredSong = featuredShelf?.songs?.firstOrNull { it.id !in state.notRecommendedIds }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.34f), Color(0xFF211B35), Color(0xFF11121B))))
                    .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(26.dp))
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isPlaying) {
                        AsyncImage(
                            model = state.activeSong.coverUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = accent, modifier = Modifier.size(34.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (state.isPlaying) "IN YOUR FLOW" else "A LITTLE SOUND ESCAPE",
                        color = accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        if (state.isPlaying) state.activeSong.title else "Find your next feeling",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (state.isPlaying) state.activeSong.artist else "Fresh picks, made for you",
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = {
                        if (state.isPlaying) {
                            vm.setFullScreenPlayer(true)
                        } else if (featuredSong != null) {
                            vm.playSongFromShelf(featuredSong, featuredShelf?.songs.orEmpty())
                        } else {
                            vm.setScreen(ScreenNav.SEARCH)
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(accent)
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.OpenInFull else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Open player" else "Play a featured song",
                        tint = Color.Black
                    )
                }
            }
        }
        if (!state.isLoggedIn) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.25f), Color(0xFF1A1626))))
                        .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sync YouTube Music", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Load your personal library & mixes in <1s", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    Button(
                        onClick = { vm.openWebLogin() },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) {
                        Text("Sign In", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                    }
                }
            }
        }

        item {
            val visibleShelves = state.homeShelves.filterIndexed { index, _ ->
                if (index == 0) state.uiCustomizations["showQuickPicks"] != false else state.uiCustomizations["showCuratedShelves"] != false
            }.map { shelf -> shelf.copy(songs = shelf.songs.filterNot { it.id in state.notRecommendedIds }) }.filter { it.songs.isNotEmpty() }
            val tabs = listOf("All") + visibleShelves.map { it.title }.distinct()
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(tabs) { _, tab ->
                    val active = state.activeHomeTab == tab
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (active) accent else Color.White.copy(alpha = 0.06f))
                            .clickable { vm.selectHomeCategory(tab) }
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = tab,
                            color = if (active) Color.Black else Color.White.copy(0.88f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (state.isSearching && state.homeShelves.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent)
                }
            }
        }

        if (!state.isSearching && state.homeShelves.isEmpty()) {
            item {
                Text(
                    "Tap to reload your music feed",
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth().clickable { vm.reloadHome() }.padding(32.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        state.homeShelves.forEachIndexed { idx, shelf ->
            val showShelf = if (idx == 0) state.uiCustomizations["showQuickPicks"] != false else state.uiCustomizations["showCuratedShelves"] != false
            val visibleSongs = shelf.songs.filterNot { it.id in state.notRecommendedIds }
            if (showShelf && visibleSongs.isNotEmpty() && (state.activeHomeTab == "All" || state.activeHomeTab == shelf.title)) {
                item(key = "shelf_$idx") {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(shelf.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            Text(
                                "Play all",
                                color = accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    visibleSongs.firstOrNull()?.let { vm.playSongFromShelf(it, visibleSongs) }
                                }
                            )
                        }
                        if (shelf.isList) {
                            visibleSongs.take(6).forEach { song ->
                                BitchordSongRowItem(
                                    song = song,
                                    isCurrent = state.activeSong.id == song.id,
                                    onClick = { vm.playSongFromShelf(song, visibleSongs) },
                                    vm = vm
                                )
                            }
                        } else {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                itemsIndexed(visibleSongs) { _, song ->
                                    if (idx == 0) {
                                        BitchordLargeQuickPickCard(
                                            song = song,
                                            onClick = { vm.playSongFromShelf(song, visibleSongs) }
                                        )
                                    } else {
                                        BitchordSquareAlbumCard(
                                            song = song,
                                            onClick = { vm.playSongFromShelf(song, visibleSongs) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "surprise_me_bottom") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.25f), Color(0xFF211B35), Color(0xFF11121B))))
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                    .clickable { vm.surpriseMe() }
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("Surprise me", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text("Find one song you haven’t heard yet", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun AccountManagerDialog(
    accounts: List<UserProfileAccount>,
    activeId: String,
    onAddAccount: () -> Unit,
    onSelectAccount: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
            color = Color(0xFF16121E)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Switch Profile", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                for (acc in accounts) {
                    val isActive = acc.id == activeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isActive) Color(0xFFFF7043).copy(alpha = 0.2f) else Color(0xFF1E1726))
                            .border(1.dp, if (isActive) Color(0xFFFF7043) else Color.Transparent, RoundedCornerShape(14.dp))
                            .clickable { onSelectAccount(acc.id) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = acc.profileImage,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(acc.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Mode: ${acc.favoriteGenre}", color = Color.Gray, fontSize = 12.sp)
                        }
                        if (isActive) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFFFF7043))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { onDismiss(); onAddAccount() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF7043))
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add YouTube Account", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun BitchordLargeQuickPickCard(song: SongItemClean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(235.dp)
            .height(275.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xFF16121E))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(26.dp))
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = song.coverUrl,
            contentDescription = song.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun BitchordSquareAlbumCard(song: SongItemClean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = song.coverUrl,
            contentDescription = song.title,
            modifier = Modifier
                .size(150.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF18181E)),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = song.title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = song.artist,
            color = Color.Gray,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun BitchordSongRowItem(
    song: SongItemClean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    vm: MainViewModel
) {
    val custom = LocalUiCustomizations.current
    val skin = LocalPrimarySkin.current
    val compact = custom["compactRows"] == true || custom["compactLibrary"] == true
    val artSize = if (custom["largerArtwork"] == true) 60.dp else when (skin.layoutFamily) {
        0 -> 52.dp
        1 -> 44.dp
        2 -> 56.dp
        3 -> 62.dp
        else -> 48.dp
    }
    val rowShape = primaryCardShape(skin)
    val subtitleParts = buildList {
        if (custom["showArtistNames"] != false) add(song.artist)
        if (custom["showAlbumNames"] == true && song.album.isNotBlank()) add(song.album)
        if (custom["showDurations"] != false) add(formatMillis(song.durationMs))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = if (compact) 3.dp else 5.dp)
            .clip(rowShape)
            .background(if (isCurrent) skin.highlight.copy(alpha = 0.16f) else primaryPanelColor(skin).copy(alpha = if (skin.layoutFamily == 0) 0.58f else 0.56f))
            .then(if (skin.layoutFamily == 0) Modifier.border(1.dp, Color.White.copy(alpha = 0.07f), rowShape) else Modifier)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (custom["showCoverArt"] != false) {
            AsyncImage(
                model = song.coverUrl,
                contentDescription = song.title,
                modifier = Modifier
                    .size(artSize)
                    .clip(if (custom["roundedCards"] == false) RoundedCornerShape(4.dp) else primaryCardShape(skin)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.isExplicit && custom["showExplicitBadge"] != false) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text("E", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = song.title,
                    color = if (isCurrent) skin.highlight else Color.White,
                    fontSize = if (custom["largeText"] == true) 17.sp else 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (subtitleParts.isNotEmpty()) {
                Text(
                    text = subtitleParts.joinToString(" • "),
                    color = if (custom["highContrast"] == true) Color.White.copy(alpha = 0.88f) else Color.Gray,
                    fontSize = if (custom["largeText"] == true) 14.sp else 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        SongOverflowMenuButton(song = song, vm = vm)
    }
}

@Composable
fun SongOverflowMenuButton(song: SongItemClean, vm: MainViewModel) {
    val state by vm.state.collectAsState()
    var expanded by remember(song.id) { mutableStateOf(false) }
    var showSaveDialog by remember(song.id) { mutableStateOf(false) }
    var playlistName by remember(song.id) { mutableStateOf("") }
    val downloadTask = state.downloadsInProgress[song.id]
    Box {
        IconButton(onClick = { expanded = true }) {
            if (downloadTask == null) {
                Icon(Icons.Default.MoreVert, contentDescription = "Song options", tint = Color.White.copy(alpha = 0.82f))
            } else if (downloadTask.progress == null) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(
                    progress = { downloadTask.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Save to playlist") },
                leadingIcon = { Icon(Icons.Outlined.PlaylistAdd, contentDescription = null) },
                onClick = { expanded = false; showSaveDialog = true }
            )
            DropdownMenuItem(
                text = { Text("Play video") },
                leadingIcon = { Icon(Icons.Outlined.VideoLibrary, contentDescription = null) },
                onClick = { expanded = false; vm.playVideo(song) }
            )
            val currentlyHidden = song.id in state.notRecommendedIds
            DropdownMenuItem(
                text = { Text(if (currentlyHidden) "Recommend" else "Don't recommend") },
                leadingIcon = { Icon(if (currentlyHidden) Icons.Outlined.ThumbUp else Icons.Outlined.ThumbDown, contentDescription = null) },
                onClick = { expanded = false; vm.setSongRecommendation(song.id, currentlyHidden) }
            )
            DropdownMenuItem(
                text = {
                    val progress = downloadTask?.progress
                    Text(
                        when {
                            progress != null -> "Downloading ${(progress * 100).toInt()}%"
                            downloadTask != null -> "Preparing download…"
                            song.isDownloaded && song.id.startsWith("local:") -> "Remove from library"
                            song.isDownloaded -> "Remove download"
                            else -> "Download"
                        }
                    )
                },
                leadingIcon = {
                    if (downloadTask != null) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(if (song.isDownloaded) Icons.Filled.CloudDone else Icons.Default.Download, contentDescription = null)
                    }
                },
                enabled = downloadTask == null,
                onClick = { expanded = false; vm.toggleDownloadSong(song) }
            )
        }
    }
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save to playlist", color = Color.White) },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    if (state.savedPlaylists.isEmpty()) {
                        Text("Create a playlist to save this song.", color = Color.White.copy(alpha = 0.7f))
                    } else {
                        state.savedPlaylists.forEach { playlist ->
                            TextButton(
                                onClick = { vm.addSongToPlaylist(playlist.id, song); showSaveDialog = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("${playlist.name} (${playlist.songs.size} songs)", color = Color.White, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        label = { Text("New playlist name") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (playlistName.isNotBlank()) {
                        vm.createPlaylist(playlistName, song)
                        playlistName = ""
                        showSaveDialog = false
                    }
                }) { Text("Create & save") }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } },
            containerColor = Color(0xFF17131F),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }
}

@Composable
fun BitchordFloatingMiniPlayer(
    state: AppStateModel,
    vm: MainViewModel,
    onExpand: () -> Unit
) {
    val song = state.activeSong
    val custom = LocalUiCustomizations.current
    val skin = state.activeSkin
    val compact = custom["compactPlayer"] == true
    val playerStyle = PLAYER_STYLE_PRESETS.firstOrNull { it.id == state.selectedPlayerStyleId } ?: PLAYER_STYLE_PRESETS.first()
    val playerAccent = playerStyle.accent
    val playerShape = primaryCardShape(skin)
    val playerBrush = when (skin.layoutFamily) {
        0 -> Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.12f), skin.highlight.copy(alpha = 0.13f), Color.White.copy(alpha = 0.06f)))
        1 -> Brush.horizontalGradient(listOf(Color(0xFF1A251E), Color(0xFF101512)))
        2 -> Brush.horizontalGradient(listOf(Color(0xFF29151A), Color(0xFF120C10)))
        3 -> Brush.horizontalGradient(listOf(Color(0xFF38212C), Color(0xFF1B1219)))
        else -> Brush.horizontalGradient(listOf(Color(0xFF252027), Color(0xFF111218)))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(playerShape)
            .background(playerBrush)
            .border(1.dp, playerAccent.copy(alpha = 0.45f), playerShape)
            .clickable { onExpand() }
    ) {
        LinearProgressIndicator(
            progress = { state.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.5.dp),
            color = playerAccent,
            trackColor = Color.White.copy(alpha = 0.08f)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (custom["showCoverArt"] != false) {
                    AsyncImage(
                        model = song.coverUrl,
                        contentDescription = song.title,
                        modifier = Modifier
                            .size(if (compact) 38.dp else 44.dp)
                            .clip(playerArtworkShape(playerStyle)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (state.isLoadingStream) "Loading song…" else song.artist,
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.videoId.isNotBlank()) {
                    IconButton(onClick = { vm.playVideo(song) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.VideoLibrary, contentDescription = "Watch video", tint = playerAccent)
                    }
                }
                IconButton(onClick = { vm.prevTrack() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = Color.White.copy(alpha = 0.85f))
                }
                IconButton(
                    onClick = { vm.togglePlay() },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(playerControlShape(playerStyle))
                        .background(playerAccent)
                ) {
                    if (state.isLoadingStream) {
                        CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.Black
                        )
                    }
                }
                IconButton(onClick = { vm.nextTrack() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun PlayerArtworkContent(
    song: SongItemClean,
    vm: MainViewModel,
    videoMode: Boolean,
    style: PlayerStylePreset,
    modifier: Modifier
) {
    val shape = if (style.layoutStyle == 2) CircleShape else playerArtworkShape(style)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(
                width = 1.5.dp,
                brush = Brush.verticalGradient(listOf(style.accent.copy(alpha = 0.72f), Color.White.copy(alpha = 0.10f))),
                shape = shape
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (videoMode) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        player = vm.activePlayerForVideoView()
                    }
                },
                update = { it.player = vm.activePlayerForVideoView() },
                modifier = Modifier.fillMaxSize().clip(shape)
            )
        } else {
            AsyncImage(
                model = song.coverUrl,
                contentDescription = song.title,
                modifier = Modifier.fillMaxSize().clip(shape),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun PlayerSignalBars(accent: Color, seed: Int, height: Dp = 34.dp) {
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val count = 36
        val gap = size.width / count
        repeat(count - 1) { index ->
            val wave = ((index * 17 + seed * 11) % 31) / 31f
            val envelope = 0.25f + 0.75f * kotlin.math.abs(kotlin.math.sin(index / 4.2 + seed.toDouble())).toFloat()
            val barHeight = size.height * (0.16f + wave * envelope * 0.78f)
            drawLine(
                color = accent.copy(alpha = 0.45f + wave * 0.5f),
                start = androidx.compose.ui.geometry.Offset(index * gap, (size.height - barHeight) / 2f),
                end = androidx.compose.ui.geometry.Offset(index * gap, (size.height + barHeight) / 2f),
                strokeWidth = (gap * 0.48f).coerceAtLeast(1f),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun BitchordFullScreenPlayer(state: AppStateModel, vm: MainViewModel) {
    val custom = LocalUiCustomizations.current
    val song = state.activeSong
    val skin = state.activeSkin
    val playerStyle = PLAYER_STYLE_PRESETS.firstOrNull { it.id == state.selectedPlayerStyleId } ?: PLAYER_STYLE_PRESETS.first()
    val playerAccent = playerStyle.accent
    var isSeeking by remember(song.id) { mutableStateOf(false) }
    var seekProgress by remember(song.id) { mutableFloatStateOf(state.progress) }
    LaunchedEffect(state.progress, isSeeking) {
        if (!isSeeking) seekProgress = state.progress
    }
    val remainingMs = (state.currentDurationMs - state.currentPosMs).coerceAtLeast(0L)
    val familyArtworkSize = when (skin.layoutFamily) { 0 -> 274; 1 -> 232; 2 -> 278; 3 -> 314; else -> 254 }
    val baseArtworkSize = familyArtworkSize + (skin.ordinal % 3 - 1) * 18
    val artworkSizeDp = (baseArtworkSize + (playerStyle.detailStyle - 2) * 4).coerceIn(216, 326)

    val bgColors = listOf(
        playerAccent.copy(alpha = (playerStyle.glow + 0.35f) * playerStyle.surfaceOpacity),
        playerStyle.background.copy(alpha = 0.95f * playerStyle.surfaceOpacity),
        Color(0xFF090A0F).copy(alpha = playerStyle.surfaceOpacity)
    )
    val bgBrush = when (skin.layoutFamily) {
        0 -> Brush.radialGradient(listOf(playerAccent.copy(alpha = 0.37f), Color(0xFF171324), Color(0xFF07080D)))
        1 -> Brush.verticalGradient(listOf(Color(0xFF172019), Color(0xFF0B0E0C), Color.Black))
        2 -> Brush.verticalGradient(listOf(Color(0xFF35131A), Color(0xFF160D12), Color.Black))
        3 -> Brush.verticalGradient(listOf(Color(0xFF512433), Color(0xFF25131D), Color(0xFF100B11)))
        else -> when (playerStyle.backgroundStyle) {
            1 -> Brush.horizontalGradient(bgColors)
            2 -> Brush.radialGradient(bgColors)
            3 -> Brush.linearGradient(bgColors)
            else -> Brush.verticalGradient(bgColors)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (custom["ambientArtwork"] != false) {
                AsyncImage(
                    model = song.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = if (skin.layoutFamily == 0) 0.48f else 0.22f
                )
            }

            Box(modifier = Modifier.fillMaxSize().background(bgBrush))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { vm.setFullScreenPlayer(false) }) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable {
                                val skins = SkinTheme.values()
                                val next = skins[(skins.indexOf(skin) + 1) % skins.size]
                                vm.setSkin(next)
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(skin.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { vm.closeAppCompletely() }) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = "Close App", tint = Color(0xFFFF8A80))
                    }
                }

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (state.playerOverlayTab) {
                        OverlayTab.LYRICS -> SyncedLyricsOverlay(
                            song = song,
                            positionMs = state.currentPosMs,
                            onSeek = { timeMs ->
                                if (state.currentDurationMs > 0L) vm.seekTo((timeMs.toFloat() / state.currentDurationMs).coerceIn(0f, 1f))
                            },
                            accent = playerAccent
                        )
                        OverlayTab.QUEUE -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Text("Up Next Queue", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                                Spacer(modifier = Modifier.height(10.dp))
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    itemsIndexed(state.playlist) { idx, item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (idx == state.currentIndex) Color.White.copy(0.14f) else Color.Transparent)
                                                .clickable { vm.loadTrack(idx, autoPlay = true) }
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AsyncImage(
                                                model = item.coverUrl,
                                                contentDescription = null,
                                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                                                contentScale = ContentScale.Crop
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                                                Text(item.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                when (playerStyle.layoutStyle) {
                                    1 -> {
                                        Box(Modifier.fillMaxWidth().height(artworkSizeDp.dp * 0.82f).clip(RoundedCornerShape(24.dp))) {
                                            PlayerArtworkContent(song, vm, state.videoMode, playerStyle, Modifier.fillMaxSize())
                                            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))).padding(14.dp)) {
                                                Text("IMMERSIVE MIX", color = playerAccent, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                                                Text(song.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                    2 -> {
                                        PlayerSignalBars(playerAccent, song.id.hashCode(), 26.dp)
                                        Spacer(Modifier.height(10.dp))
                                        PlayerArtworkContent(song, vm, state.videoMode, playerStyle, Modifier.size((artworkSizeDp * 0.72f).dp))
                                        Spacer(Modifier.height(10.dp))
                                        PlayerSignalBars(playerAccent, song.artist.hashCode(), 18.dp)
                                    }
                                    3 -> {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                            PlayerArtworkContent(song, vm, state.videoMode, playerStyle, Modifier.size(146.dp))
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("SIDE A · NOW PLAYING", color = playerAccent, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                                                Text(song.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                Text(song.artist, color = Color.White.copy(alpha = 0.64f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                PlayerSignalBars(playerAccent, song.id.hashCode(), 28.dp)
                                            }
                                        }
                                    }
                                    4 -> {
                                        PlayerSignalBars(playerAccent, song.artist.hashCode(), 42.dp)
                                        Spacer(Modifier.height(8.dp))
                                        PlayerArtworkContent(song, vm, state.videoMode, playerStyle, Modifier.fillMaxWidth(0.88f).height((artworkSizeDp * 0.72f).dp))
                                    }
                                    5 -> {
                                        Box(contentAlignment = Alignment.Center) {
                                            PlayerSignalBars(playerAccent.copy(alpha = 0.65f), song.title.hashCode(), artworkSizeDp.dp)
                                            PlayerArtworkContent(song, vm, state.videoMode, playerStyle, Modifier.size((artworkSizeDp * 0.68f).dp))
                                        }
                                    }
                                    else -> PlayerArtworkContent(song, vm, state.videoMode, playerStyle, Modifier.size(artworkSizeDp.dp))
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                if (custom["showCodecLabels"] != false) {
                                    Text(song.codecInfo, color = Color.White.copy(alpha = 0.68f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(song.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (custom["showArtistNames"] != false) {
                                Text(song.artist, color = Color.White.copy(alpha = 0.72f), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val isFav = state.favoriteIds.contains(song.id)
                            IconButton(
                                onClick = { vm.toggleFavorite(song.id) },
                                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))
                            ) {
                                Icon(
                                    imageVector = if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = "Favorite",
                                    tint = if (isFav) Color(0xFFFF4081) else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            SongOverflowMenuButton(song = song, vm = vm)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Slider(
                        value = if (isSeeking) seekProgress else state.progress,
                        onValueChange = {
                            seekProgress = it
                            isSeeking = true
                        },
                        onValueChangeFinished = {
                            vm.seekTo(seekProgress)
                            isSeeking = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = playerAccent,
                            activeTrackColor = playerAccent,
                            inactiveTrackColor = playerAccent.copy(alpha = 0.26f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (custom["showWaveform"] == true) 34.dp else 22.dp)
                            .clip(primaryCardShape(skin))
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(formatMillis(state.currentPosMs), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        Text("${state.activeSong.codecInfo.substringBefore(" • ")} • 1.00×", color = playerAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text("-${formatMillis(remainingMs)}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { vm.prevTrack() }) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(42.dp))
                        }

                        IconButton(
                            onClick = { vm.togglePlay() },
                            modifier = Modifier
                                .size(if (custom["largeTransportControls"] == true) 78.dp else 68.dp)
                                .clip(playerControlShape(playerStyle))
                                .background(playerAccent)
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.Black,
                                modifier = Modifier.size(if (custom["largeTransportControls"] == true) 44.dp else 38.dp)
                            )
                        }

                        IconButton(onClick = { vm.nextTrack() }) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(42.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = vm::openAndroidAuto) {
                            Icon(Icons.Default.DirectionsCar, contentDescription = "Android Auto", tint = Color.White.copy(alpha = 0.85f))
                        }
                        IconButton(onClick = vm::toggleVideoMode) {
                            Icon(
                                imageVector = Icons.Outlined.VideoLibrary,
                                contentDescription = "Video Mode",
                                tint = if (state.videoMode) playerAccent else Color.White.copy(0.6f)
                            )
                        }
                        IconButton(
                            onClick = { vm.togglePlayerOverlayTab(OverlayTab.LYRICS) },
                            enabled = !song.id.startsWith("radio:"),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Subtitles,
                                contentDescription = "Lyrics",
                                tint = if (state.playerOverlayTab == OverlayTab.LYRICS) playerAccent else Color.White.copy(0.6f)
                            )
                        }
                        if (custom["showQueueIndicator"] != false) {
                            IconButton(onClick = { vm.togglePlayerOverlayTab(OverlayTab.QUEUE) }) {
                                Icon(
                                    imageVector = Icons.Filled.FormatListBulleted,
                                    contentDescription = "Queue",
                                    tint = if (state.playerOverlayTab == OverlayTab.QUEUE) playerAccent else Color.White.copy(0.6f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BitChordBottomNavPill(state: AppStateModel, vm: MainViewModel) {
    val skin = state.activeSkin
    val accent = skin.highlight
    val navShape = primaryCardShape(skin)
    val navItems = listOf(
        Triple(ScreenNav.HOME, "Home", Icons.Filled.Home),
        Triple(ScreenNav.EXPLORE, "Explore", Icons.Outlined.GridView),
        Triple(ScreenNav.LIBRARY, "Library", Icons.Outlined.LibraryMusic),
        Triple(ScreenNav.SEARCH, "Search", Icons.Outlined.Search),
        Triple(ScreenNav.OFFLINE, "Vault", Icons.Default.Download),
        Triple(ScreenNav.RADIO, "Radio", Icons.Default.Radio),
        Triple(ScreenNav.SETTINGS, "Studio", Icons.Outlined.Tune)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (skin.layoutFamily == 3) 70.dp else 64.dp)
            .clip(navShape)
            .background(if (skin.layoutFamily == 0) Color(0xFF17151F).copy(alpha = 0.73f) else primaryPanelColor(skin).copy(alpha = 0.97f))
            .border(1.dp, if (skin.layoutFamily == 0) Color.White.copy(alpha = 0.16f) else accent.copy(alpha = 0.16f), navShape)
            .padding(5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (item in navItems) {
            val screen = item.first
            val label = item.second
            val icon = item.third
            val selected = state.currentScreen == screen ||
                (screen == ScreenNav.SETTINGS && state.currentScreen in setOf(
                    ScreenNav.APP_APPEARANCE, ScreenNav.PLAYER_STYLES,
                    ScreenNav.ANDROID_AUTO_SETTINGS, ScreenNav.EQUALIZER, ScreenNav.V2_ROADMAP
                ))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(primaryCardShape(skin))
                    .background(if (selected) accent.copy(alpha = if (skin.layoutFamily == 0) 0.28f else 0.22f) else Color.Transparent)
                    .clickable { vm.setScreen(screen) },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, contentDescription = label, tint = if (selected) accent else Color.Gray, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(label, color = if (selected) Color.White else Color.Gray, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun BitChordSettingsScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var lyricProviderOrder by remember { mutableStateOf(LyricsRepository.loadProviderOrder(context)) }
    var losslessSourceUrl by remember(state.losslessSourceUrl) { mutableStateOf(state.losslessSourceUrl) }
    var losslessSourceToken by remember { mutableStateOf("") }
    var regionMenuExpanded by remember { mutableStateOf(false) }
    var showVoiceControlGuide by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        vm.setDownloadDirectory(uri)
    }
    val accent = state.activeSkin.highlight
    val folderName = state.downloadDirectoryUri?.let { value ->
        runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(value)).substringAfterLast(':') }.getOrNull()
    }?.takeIf { it.isNotBlank() } ?: "Choose a folder for saved music"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("KILO STUDIO", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Settings & Tuning", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            }
            Button(
                onClick = { vm.closeAppCompletely() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF331820)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = Color(0xFFFF8A80), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Quit App", color = Color(0xFFFF8A80), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        SettingsGroupCard {
            SettingsRowItem(
                icon = Icons.Default.Tune,
                title = "Personalization",
                subtitle = "Edit genres, followed artists, moods and discovery settings",
                trailingValue = "Edit",
                onClick = vm::openPersonalizationWizard
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
            SettingsRowItem(
                icon = Icons.Default.Refresh,
                title = "Reset Recommendations",
                subtitle = "Clear learned discovery signals and start fresh",
                onClick = vm::resetRecommendations
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
            SettingsRowItem(
                icon = Icons.Default.AutoAwesome,
                title = "Rebuild Profile",
                subtitle = "Use followed artists and liked songs to refresh your profile",
                onClick = vm::rebuildProfile
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
            SettingsRowItem(
                icon = Icons.Default.Share,
                title = "Export Profile",
                subtitle = "Share a copy of your local personalization data",
                onClick = vm::exportProfile
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
            SettingsRowItem(
                icon = Icons.Default.Palette,
                title = "App Appearance",
                subtitle = "15 complete layouts for home, library and player",
                trailingValue = "Customize",
                onClick = { vm.setScreen(ScreenNav.APP_APPEARANCE) }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
            SettingsRowItem(
                icon = Icons.Default.Person,
                title = "YouTube Music Account",
                subtitle = if (state.isLoggedIn) "Connected & synced" else "Not signed in",
                onClick = { vm.openWebLogin() }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
            SettingsRowItem(
                icon = Icons.Default.SwitchAccount,
                title = "Switch Profiles",
                subtitle = "Manage multiple listeners",
                onClick = { vm.toggleAccountManager(true) }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
            SettingsRowItem(
                icon = Icons.Default.Palette,
                title = "Player Style Library",
                subtitle = "${PLAYER_STYLE_PRESETS.size} visual presets",
                onClick = { vm.setScreen(ScreenNav.PLAYER_STYLES) }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
            SettingsRowItem(
                icon = Icons.Default.DirectionsCar,
                title = "Android Auto",
                subtitle = "25 browse layouts and driving options",
                trailingValue = "Customize",
                onClick = { vm.setScreen(ScreenNav.ANDROID_AUTO_SETTINGS) }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
            SettingsRowItem(
                icon = Icons.Default.Map,
                title = "Kilo V2 Roadmap",
                subtitle = "See the performance, audio and Auto milestones",
                trailingValue = "View",
                onClick = { vm.setScreen(ScreenNav.V2_ROADMAP) }
            )
        }

        SettingsSectionHeader("VOICE CONTROL")
        SettingsGroupCard {
            SettingsRowItem(
                icon = Icons.Default.Mic,
                title = "Gemini & voice commands",
                subtitle = "Set up KILO for hands-free music requests",
                trailingValue = "Setup guide",
                onClick = { showVoiceControlGuide = true }
            )
            Text(
                "KILO supports Android Auto media search. Gemini controls which services appear in its provider list.",
                color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            )
        }
        if (showVoiceControlGuide) {
            AlertDialog(
                onDismissRequest = { showVoiceControlGuide = false },
                title = { Text("Set up voice playback", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "If Gemini shows a music services or provider setting, open Gemini settings, choose Music, and select KILO if it is listed. Then try saying, ‘Play [song or artist] on KILO.’\n\nIf KILO is not listed, Gemini does not currently offer it as a selectable provider on this device. You can still browse KILO from Android Auto’s media apps. Sign in to KILO for online search; while signed out, voice search can use downloaded songs only."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showVoiceControlGuide = false }) {
                        Text("Got it", color = accent)
                    }
                },
                containerColor = Color(0xFF1C1829),
                titleContentColor = Color.White,
                textContentColor = Color.White.copy(alpha = 0.82f)
            )
        }
        SettingsSectionHeader("MUSIC DISCOVERY")
        SettingsGroupCard {
            Box {
                SettingsRowItem(
                    icon = Icons.Default.Public,
                    title = "Music country / region",
                    subtitle = if (state.musicRegionCode == "AUTO") "Use device country (${java.util.Locale.getDefault().country.ifBlank { "US" }})" else "Use YouTube Music catalog for ${java.util.Locale("", state.musicRegionCode).getDisplayCountry(java.util.Locale.getDefault())}",
                    trailingValue = if (state.musicRegionCode == "AUTO") "Auto" else java.util.Locale("", state.musicRegionCode).getDisplayCountry(java.util.Locale.getDefault()),
                    onClick = { regionMenuExpanded = true }
                )
                DropdownMenu(expanded = regionMenuExpanded, onDismissRequest = { regionMenuExpanded = false }) {
                    musicRegionOptions().forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { regionMenuExpanded = false; vm.setMusicRegion(code) },
                            trailingIcon = if (state.musicRegionCode == code) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null
                        )
                    }
                }
            }
            Text("Country changes YouTube Music recommendations and search results. Auto uses your device's locale; it does not access GPS.", color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }

        SettingsSectionHeader("LYRICS PROVIDERS")
        SettingsGroupCard {
            Text(
                "Lyrics are checked from top to bottom. Better Lyrics stays first by default; move providers to change lookup priority.",
                color = Color.White.copy(alpha = 0.68f), fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            lyricProviderOrder.forEachIndexed { index, providerId ->
                val provider = LyricsRepository.providers.firstOrNull { it.id == providerId } ?: return@forEachIndexed
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${index + 1}.", color = accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                    Text(provider.title, color = Color.White, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            if (index > 0) {
                                val changed = lyricProviderOrder.toMutableList().also {
                                    val item = it.removeAt(index)
                                    it.add(index - 1, item)
                                }
                                LyricsRepository.updateProviderOrder(context, changed)
                                lyricProviderOrder = LyricsRepository.loadProviderOrder(context)
                            }
                        },
                        enabled = index > 0
                    ) { Icon(Icons.Default.ArrowUpward, contentDescription = "Move ${provider.title} up", tint = if (index > 0) accent else Color.Gray) }
                    IconButton(
                        onClick = {
                            if (index < lyricProviderOrder.lastIndex) {
                                val changed = lyricProviderOrder.toMutableList().also {
                                    val item = it.removeAt(index)
                                    it.add(index + 1, item)
                                }
                                LyricsRepository.updateProviderOrder(context, changed)
                                lyricProviderOrder = LyricsRepository.loadProviderOrder(context)
                            }
                        },
                        enabled = index < lyricProviderOrder.lastIndex
                    ) { Icon(Icons.Default.ArrowDownward, contentDescription = "Move ${provider.title} down", tint = if (index < lyricProviderOrder.lastIndex) accent else Color.Gray) }
                }
                if (index < lyricProviderOrder.lastIndex) HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        SettingsSectionHeader("LOSSLESS STREAMING")
        SettingsGroupCard {
            Text(
                "Connect a compatible source you’re authorized to use. KILO verifies a title and artist match and only labels playback FLAC/ALAC when the source reports that format. YouTube Music remains the fallback.",
                color = Color.White.copy(alpha = 0.68f), fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            OutlinedTextField(
                value = losslessSourceUrl,
                onValueChange = { losslessSourceUrl = it },
                label = { Text("Source URL") },
                placeholder = { Text("https://your-source.example") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = accent, unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                    focusedLabelColor = accent, unfocusedLabelColor = Color.LightGray
                )
            )
            OutlinedTextField(
                value = losslessSourceToken,
                onValueChange = { losslessSourceToken = it },
                label = { Text(if (state.losslessSourceHasToken) "Access token (saved securely; blank keeps current)" else "Optional access token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = accent, unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                    focusedLabelColor = accent, unfocusedLabelColor = Color.LightGray
                )
            )
            Text(state.losslessSourceStatus, color = accent, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.configureLosslessSource(losslessSourceUrl, losslessSourceToken); losslessSourceToken = "" }) {
                    Text("Test & save")
                }
                if (state.losslessSourceEnabled) {
                    TextButton(onClick = vm::disableLosslessSource) { Text("Use YouTube only") }
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))
        Spacer(modifier = Modifier.height(22.dp))
        SettingsSectionHeader("CONTENT FILTERS")
        SettingsGroupCard {
            CustomizationToggleRow(
                title = "18+ / Explicit content filter",
                description = "Hide tracks identified as explicit in YouTube Music metadata",
                checked = state.filterMatureContent,
                accent = accent,
                onCheckedChange = vm::setMatureContentFilter
            )
        }
        SettingsSectionHeader("DOWNLOADS & STORAGE")
        SettingsGroupCard {
            SettingsRowItem(
                icon = Icons.Default.Folder,
                title = "Download Folder",
                subtitle = folderName,
                trailingValue = "Change",
                onClick = { picker.launch(null) }
            )
            CustomizationToggleRow(
                title = "Wi-Fi only downloads",
                description = "Save mobile data when downloading tracks",
                checked = state.downloadWifiOnly,
                accent = accent,
                onCheckedChange = vm::toggleDownloadWifiOnly
            )
        }

        Spacer(modifier = Modifier.height(22.dp))
        SettingsSectionHeader("SOUND & PLAYBACK")
        SettingsGroupCard {
            SettingsRowItem(
                icon = Icons.Outlined.Equalizer,
                title = "Kilo Equalizer",
                subtitle = "Adjust the active player’s device-supported frequency bands",
                trailingValue = "Open",
                onClick = vm::openEqualizer
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
            SettingsRowItem(
                icon = Icons.Outlined.Equalizer,
                title = "System Equalizer",
                subtitle = "Launch device DSP audio effects",
                onClick = vm::openSystemEqualizer
            )
            CustomizationToggleRow(
                title = "Automix crossfade",
                description = "Smoothly blend transitions between tracks",
                checked = state.autoMixEnabled,
                accent = accent,
                onCheckedChange = vm::setAutoMixEnabled
            )
        }

        SettingsSectionHeader("APP UPDATES")
        SettingsGroupCard {
            Text(
                state.updateCheckMessage,
                color = if (state.updateAvailable) accent else Color.White.copy(alpha = 0.68f),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = vm::checkForUpdates, enabled = !state.isCheckingForUpdates) {
                    if (state.isCheckingForUpdates) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.isCheckingForUpdates) "Checking…" else "Check GitHub")
                }
                if (state.latestReleaseUrl.isNotBlank()) {
                    TextButton(onClick = { vm.openReleaseUrl(state.latestReleaseUrl) }) { Text("View release") }
                }
                if (state.latestApkUrl.isNotBlank()) {
                    TextButton(onClick = { vm.openReleaseUrl(state.latestApkUrl) }) { Text("Get APK") }
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))
        UI_CUSTOMIZATION_OPTIONS.map { it.group }.distinct().forEach { group ->
            SettingsSectionHeader(group)
            SettingsGroupCard {
                val groupOptions = UI_CUSTOMIZATION_OPTIONS.filter { it.group == group }
                groupOptions.forEachIndexed { index, option ->
                    CustomizationToggleRow(
                        title = option.title,
                        description = option.description,
                        checked = state.uiCustomizations[option.key] ?: option.enabledByDefault,
                        accent = accent,
                        onCheckedChange = { vm.toggleCustomization(option.key, it) }
                    )
                    if (index < groupOptions.size - 1) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun EqualizerScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val eq = state.equalizerSnapshot
    val accent = state.activeSkin.highlight
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.setScreen(ScreenNav.SETTINGS) }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back to settings", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("SOUND", color = accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp)
                Text("Kilo Equalizer", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        SettingsGroupCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Equalizer", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(eq.message, color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp)
                }
                Switch(checked = eq.enabled, onCheckedChange = vm::setEqualizerEnabled, enabled = eq.available)
            }
        }
        if (eq.presets.isNotEmpty()) {
            SettingsSectionHeader("PRESETS")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                itemsIndexed(eq.presets) { index, preset ->
                    FilterChip(
                        selected = eq.selectedPreset == index,
                        onClick = { vm.applyEqualizerPreset(index) },
                        label = { Text(preset) },
                        enabled = eq.available
                    )
                }
            }
        }
        if (eq.bands.isNotEmpty()) {
            SettingsSectionHeader("FREQUENCY BANDS")
            SettingsGroupCard {
                eq.bands.forEachIndexed { rowIndex, band ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (band.centerFrequencyHz >= 1000) "${band.centerFrequencyHz / 1000.0} kHz" else "${band.centerFrequencyHz} Hz", color = Color.White, modifier = Modifier.weight(1f))
                            Text("${"%.1f".format(band.levelMilliBels / 100f)} dB", color = accent, fontSize = 12.sp)
                        }
                        Slider(
                            value = band.levelMilliBels.toFloat(),
                            onValueChange = { vm.setEqualizerBand(band.index, it.toInt()) },
                            valueRange = eq.minimumLevelMilliBels.toFloat()..eq.maximumLevelMilliBels.toFloat(),
                            enabled = eq.available && eq.enabled,
                            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
                        )
                    }
                    if (rowIndex < eq.bands.lastIndex) HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            Text("Band count and frequency range depend on the audio effects available on this phone.", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp))
        } else {
            Text("Start a song, then return here to connect the equalizer to the active playback session.", color = Color.White.copy(alpha = 0.68f), fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
fun V2RoadmapScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val accent = state.activeSkin.highlight
    val items = listOf(
        Triple("Playback foundation", "Playback reliability, seeking, offline cache and queue recovery", "IN PLACE"),
        Triple("Lyrics and community playlists", "Provider fallback, lyric source order and family playlist discovery", "IN PLACE"),
        Triple("Radio reliability", "Format-aware streams, resilient buffering and station recovery", "IN PROGRESS"),
        Triple("Equalizer", "Device-supported bands, presets and saved sound settings", "IN PROGRESS"),
        Triple("Library scale", "Background indexing, paging and artwork/metadata preloading", "PLANNED"),
        Triple("Downloads", "Queue controls, pause/resume and album or playlist downloads", "PLANNED"),
        Triple("Android Auto", "Faster browse/search, quick play and cached driving shelves", "PLANNED"),
        Triple("Themes and listening stats", "Composable visual styles, listening history and recaps", "PLANNED"),
        Triple("DJ and smart mixes", "Crossfade controls, smart queueing and themed mixes", "PLANNED")
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.setScreen(ScreenNav.SETTINGS) }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back to settings", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("WHAT'S NEXT", color = accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp)
                Text("Kilo V2 Roadmap", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Text("A practical upgrade path focused on reliability and speed first. Planned items are targets, not promises of features already shipped.", color = Color.White.copy(alpha = 0.67f), fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        Spacer(Modifier.height(12.dp))
        items.forEach { (title, description, status) ->
            SettingsGroupCard {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(description, color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    Text(status, color = accent, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 8.dp, top = 3.dp))
                }
            }
            Spacer(Modifier.height(9.dp))
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
fun AppAppearanceScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val accent = state.activeSkin.highlight
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.setScreen(ScreenNav.SETTINGS) }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back to settings", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("KILO DESIGN STUDIO", color = accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp)
                Text("App Appearance", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Text(
            "15 complete layouts. Your selection reshapes Home, browse cards, library rows, navigation and the player. The separate Player Style Library still controls its extra player treatments.",
            color = Color.White.copy(alpha = 0.68f), fontSize = 13.sp, lineHeight = 19.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
        SettingsSectionHeader("CHOOSE A COMPLETE LOOK")
        SkinTheme.values().toList().chunked(3).forEach { rowSkins ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                rowSkins.forEach { skin ->
                    val selected = state.activeSkin == skin
                    val shape = primaryCardShape(skin)
                    Column(
                        modifier = Modifier.weight(1f).heightIn(min = 128.dp).clip(shape)
                            .background(if (skin.layoutFamily == 0) Brush.linearGradient(listOf(skin.highlight.copy(alpha = 0.25f), Color(0xFF181A24))) else primaryBackdrop(skin))
                            .border(1.dp, if (selected) skin.highlight else Color.White.copy(alpha = 0.08f), shape)
                            .clickable { vm.setSkin(skin) }.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(skin.highlight))
                            Text(skin.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        AppLayoutPreview(skin.ordinal, skin.highlight)
                        Text(skin.description, color = Color.White.copy(alpha = 0.70f), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(if (selected) "ACTIVE" else "TAP TO APPLY", color = skin.highlight, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                    }
                }
                repeat(3 - rowSkins.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(9.dp))
        }
    }
}

@Composable
private fun AppLayoutPreview(index: Int, accent: Color) {
    val panel = Color(0xFF11151E)
    val shapes = listOf(24.dp, 6.dp, 16.dp, 28.dp, 20.dp)
    Column(
        Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF080B11)).padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(when (index) { 0 -> "Listen Now"; 1 -> "Good afternoon"; 2 -> "Home"; 3 -> "Listen Now"; 9 -> "For you"; 14 -> "Your stream"; else -> "Discover" }, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(2) { Box(Modifier.size(5.dp).clip(CircleShape).background(accent.copy(alpha = 0.85f))) }
            }
        }
        when (index) {
            0, 4, 5 -> {
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(shapes[index % shapes.size])).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.85f), panel, accent.copy(alpha = 0.24f))))) {
                    Column(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                        Text(if (index == 0) "FROSTED NOW PLAYING" else "MADE FOR YOUR MOMENT", color = Color.White.copy(0.72f), fontSize = 5.sp)
                        Text("Your soundtrack", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            1, 6, 7, 8 -> {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(if (index == 8) 3 else 2) { i ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(shapes[index % shapes.size])).background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.9f - i * 0.15f), panel))))
                            Box(Modifier.fillMaxWidth(0.7f).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.55f)))
                        }
                    }
                }
            }
            2, 9, 10, 11 -> {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { i ->
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(shapes[index % shapes.size])).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.75f - i * 0.15f), panel))))
                    }
                }
            }
            3, 12, 13 -> {
                Row(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(shapes[index % shapes.size])).background(panel).padding(5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxHeight().weight(0.8f).clip(RoundedCornerShape(7.dp)).background(Brush.linearGradient(listOf(accent, Color(0xFF20152A)))))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (index == 12) "HI-RES ALBUMS" else "PICKED FOR YOU", color = accent, fontSize = 5.sp)
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(0.8f)))
                        Box(Modifier.fillMaxWidth(0.6f).height(3.dp).clip(CircleShape).background(Color.White.copy(0.3f)))
                    }
                }
            }
            else -> {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(3) { i ->
                        Row(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(5.dp)).background(panel).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(accent.copy(alpha = 0.7f)))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Box(Modifier.fillMaxWidth(0.55f + i * 0.1f).height(3.dp).clip(CircleShape).background(Color.White.copy(0.75f)))
                                Box(Modifier.fillMaxWidth(0.35f).height(2.dp).clip(CircleShape).background(Color.White.copy(0.3f)))
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            repeat(4) { i -> Box(Modifier.width(if (i == index % 4) 16.dp else 9.dp).height(3.dp).clip(CircleShape).background(if (i == index % 4) accent else Color.White.copy(0.26f))) }
        }
    }
}

@Composable
private fun AutoLayoutPreview(index: Int, accent: Color) {
    val shell = RoundedCornerShape(9.dp)
    val tile = RoundedCornerShape(if (index % 3 == 0) 8.dp else 3.dp)
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(shell).background(Color(0xFF070B11)).border(1.dp, accent.copy(alpha = 0.3f), shell).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Column(Modifier.width(12.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
            repeat(3) { Box(Modifier.size(5.dp).clip(CircleShape).background(if (it == index % 3) accent else Color.White.copy(0.55f))) }
        }
        when (index) {
            0, 1, 2, 3, 14, 15, 16, 17, 22 -> {
                Row(Modifier.fillMaxSize().clip(tile).background(Color(0xFF111A25)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.fillMaxHeight().weight(if (index == 1 || index == 22) 1f else 0.72f).clip(tile).background(Brush.linearGradient(listOf(accent, Color(0xFF151D34), accent.copy(alpha = 0.38f)))))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.Start) {
                        Box(Modifier.fillMaxWidth(0.78f).height(4.dp).clip(CircleShape).background(Color.White.copy(0.88f)))
                        Box(Modifier.fillMaxWidth(0.42f).height(3.dp).clip(CircleShape).background(Color.White.copy(0.4f)))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(3) { Box(Modifier.size(if (it == 1) 9.dp else 5.dp).clip(CircleShape).background(if (it == 1) accent else Color.White)) } }
                    }
                    if (index == 3 || index in 14..17) Box(Modifier.width(34.dp).fillMaxHeight().clip(tile).background(Color(0xFF192334)))
                }
            }
            4, 5, 9, 10, 11, 18, 21, 24 -> {
                Column(Modifier.fillMaxSize().clip(tile).background(Color(0xFF101722)).padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (index == 5) repeat(3) { Box(Modifier.fillMaxWidth(if (it == 1) 0.55f else 0.85f).height(3.dp).clip(CircleShape).background(Color.White.copy(0.66f))) }
                    else repeat(3) { i -> Row(Modifier.fillMaxWidth().weight(1f).clip(tile).background(Color(0xFF172130)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Box(Modifier.size(10.dp).clip(tile).background(accent.copy(alpha = 0.8f - i * 0.12f))); Box(Modifier.fillMaxWidth(0.62f).height(3.dp).clip(CircleShape).background(Color.White.copy(0.72f))) } }
                }
            }
            7, 19, 20, 23 -> {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(2) { r -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(3) { c -> Box(Modifier.weight(1f).fillMaxHeight().clip(tile).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.75f - (r + c) * 0.1f), Color(0xFF111B28))))) } } }
                }
            }
            else -> {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { i -> Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) { Box(Modifier.fillMaxWidth().weight(1f).clip(tile).background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.85f - i * 0.15f), Color(0xFF121A2A))))); Box(Modifier.fillMaxWidth(0.75f).height(3.dp).clip(CircleShape).background(Color.White.copy(0.5f))) } }
                }
            }
        }
    }
}

@Composable
fun AndroidAutoSettingsScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val accent = state.activeSkin.highlight
    Column(
        modifier = Modifier.fillMaxSize().background(primaryBackdrop(state.activeSkin)).statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.setScreen(ScreenNav.SETTINGS) }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Column {
                Text("KILO IN THE CAR", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Android Auto", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Choose which music sections appear first and how many recommendation shelves KILO shows. Android Auto adapts the screen for your car.",
                    color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
            item {
                SettingsGroupCard {
                    CustomizationToggleRow(
                        title = "Play when Android Auto connects",
                        description = "Resume the current queue when the car opens Kilo. Off by default.",
                        checked = state.autoPlayOnCarConnect,
                        accent = accent,
                        onCheckedChange = vm::setAutoPlayOnCarConnect
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(horizontal = 16.dp))
                    CustomizationToggleRow(
                        title = "Pause or lower music for calls",
                        description = "Use Android audio focus so calls and navigation can be heard.",
                        checked = state.pauseForCalls,
                        accent = accent,
                        onCheckedChange = vm::setPauseForCalls
                    )
                }
            }
            item { SettingsSectionHeader("BROWSE ORDER") }
            AUTO_LAYOUT_PROFILES.forEachIndexed { profileIndex, profile ->
                item(key = "auto_layout_profile_$profileIndex") {
                    SettingsGroupCard {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(profile.first, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(profile.second, color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp, lineHeight = 17.sp)
                            (2..6).toList().chunked(3).forEach { rowCounts ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowCounts.forEach { shelfCount ->
                                        val mode = "${profileIndex + 1}|$shelfCount|${profile.first}"
                                        FilterChip(
                                            selected = state.autoLayoutMode == mode,
                                            onClick = { vm.setAutoLayoutMode(mode) },
                                            label = { Text("$shelfCount shelves") },
                                            leadingIcon = if (state.autoLayoutMode == mode) ({
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }) else null
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerStylePreview(style: PlayerStylePreset) {
    val shape = RoundedCornerShape(style.cornerRadius.coerceAtMost(22).dp)
    val artShape = if (style.layoutStyle == 2) CircleShape else playerArtworkShape(style)
    Box(
        Modifier.fillMaxWidth().height(108.dp).clip(shape)
            .background(Brush.linearGradient(listOf(style.accent.copy(alpha = style.glow + 0.22f), style.background, Color(0xFF080A10))))
            .padding(8.dp)
    ) {
        when (style.layoutStyle) {
            0 -> Row(Modifier.fillMaxSize().clip(shape).background(style.background.copy(alpha = 0.68f)).padding(7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(53.dp).clip(artShape).background(Brush.linearGradient(listOf(style.accent, Color(0xFF20263D), style.background))))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("NOW PLAYING", color = style.accent, fontSize = 6.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    Text("Midnight Drive", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("KILO · 3:45", color = Color.White.copy(0.55f), fontSize = 6.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { repeat(3) { Box(Modifier.size(if (it == 1) 12.dp else 7.dp).clip(playerControlShape(style)).background(if (it == 1) style.accent else Color.White.copy(0.5f))) } }
                }
            }
            1 -> Box(Modifier.fillMaxSize().clip(shape).background(Brush.linearGradient(listOf(style.accent, style.background, Color(0xFF08111D))))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(0.48f).align(Alignment.CenterStart).padding(5.dp).clip(artShape).background(Brush.verticalGradient(listOf(Color(0xFF6DB7D9), style.accent, Color(0xFF131326)))))
                Column(Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.52f).padding(end = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("FULL BLEED", color = style.accent, fontSize = 6.sp, fontWeight = FontWeight.Bold)
                    Text("Midnight\nDrive", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 10.sp)
                    PlayerSignalBars(style.accent, style.id.hashCode(), 16.dp)
                }
            }
            2 -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(62.dp).border(2.dp, style.accent, CircleShape).padding(4.dp).clip(CircleShape).background(Brush.radialGradient(listOf(style.accent, Color(0xFF0A0A12), style.background))))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("VINYL / HALO", color = style.accent, fontSize = 6.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Midnight Drive", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    PlayerSignalBars(style.accent, style.id.length, 19.dp)
                }
            }
            3 -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.fillMaxHeight().weight(0.85f).clip(artShape).background(Brush.linearGradient(listOf(style.accent, Color(0xFF14223A), style.background))))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("SIDE PANEL", color = style.accent, fontSize = 6.sp, fontWeight = FontWeight.Bold)
                    Text("Midnight Drive", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Text("KILO", color = Color.White.copy(0.55f), fontSize = 6.sp)
                    PlayerSignalBars(style.accent, style.cornerRadius, 22.dp)
                }
            }
            4 -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                Text("SPECTRUM DECK", color = style.accent, fontSize = 6.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.3.sp)
                PlayerSignalBars(style.accent, style.cornerRadius + style.layoutStyle, 34.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { repeat(5) { Box(Modifier.size(if (it == 2) 11.dp else 7.dp).clip(playerControlShape(style)).background(if (it == 2) style.accent else Color.White.copy(0.65f))) } }
            }
            else -> Box(Modifier.fillMaxSize().clip(shape).background(style.background.copy(alpha = 0.65f))) {
                PlayerSignalBars(style.accent.copy(alpha = 0.72f), style.id.hashCode(), 72.dp)
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(50)).background(style.accent.copy(alpha = 0.28f)).border(1.dp, style.accent.copy(alpha = 0.65f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(25.dp).clip(artShape).background(Brush.linearGradient(listOf(style.accent, Color(0xFF182039)))))
                    Column(Modifier.weight(1f)) { Text("Midnight Drive", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold); Text("KILO · LIVE", color = style.accent, fontSize = 5.sp) }
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = style.accent, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
fun PlayerStylesScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B10))
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.setScreen(ScreenNav.SETTINGS) }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Player Styles (${PLAYER_STYLE_PRESETS.size})", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PLAYER_STYLE_PRESETS.chunked(2).forEach { rowStyles ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowStyles.forEach { preset ->
                            val selected = preset.id == state.selectedPlayerStyleId
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(preset.background)
                                    .border(1.5.dp, if (selected) preset.accent else Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
                                    .clickable { vm.setPlayerStyle(preset.id) }
                                    .padding(14.dp)
                            ) {
                                PlayerStylePreview(preset)
                                Spacer(Modifier.height(8.dp))
                                Text(preset.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(if (selected) "Active" else "Tap to apply", color = if (selected) preset.accent else Color.Gray, fontSize = 11.sp)
                            }
                        }
                        if (rowStyles.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun CustomizationToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, lineHeight = 15.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = accent)
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        color = Color.Gray,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    val glass = LocalUiCustomizations.current["glassPanels"] != false
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (glass) Color(0xFF1C1829).copy(alpha = 0.82f) else Color(0xFF15121E)),
        content = content
    )
}

@Composable
fun SettingsRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    badgeText: String? = null,
    trailingValue: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (badgeText != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2C2C34))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(badgeText, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(subtitle, color = Color.Gray, fontSize = 13.sp)
            }
        }
        if (trailingValue != null) {
            Text(trailingValue, color = Color.Gray, fontSize = 15.sp, modifier = Modifier.padding(end = 6.dp))
        }
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun ExploreScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val skin = state.activeSkin
    val accent = skin.highlight
    Column(modifier = Modifier.fillMaxSize().background(primaryBackdrop(skin)).padding(top = 16.dp)) {
        Text(when (skin.layoutFamily) { 0 -> "Explore, in glass"; 1 -> "Browse everything"; 2 -> "Explore & Vibe Reels"; 3 -> "Browse new sounds"; else -> "The sound stream" }, color = Color.White, fontSize = if (skin.layoutFamily == 3) 30.sp else 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(modifier = Modifier.height(14.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val genres = listOf("Discover", "Global Top 50", "80s & 90s Classics", "Synthwave", "Indie Chill", "Alternative", "Electronic", "R&B")
            itemsIndexed(genres) { _, mood ->
                Box(
                    modifier = Modifier
                        .clip(primaryCardShape(skin))
                        .background(if (state.selectedMood == mood) accent else primaryPanelColor(skin))
                        .then(if (skin.layoutFamily == 0) Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), primaryCardShape(skin)) else Modifier)
                        .clickable {
                            vm.setDiscoveryMood(mood)
                            if (mood != "Discover") {
                                vm.onSearchQueryChanged(mood)
                                vm.setScreen(ScreenNav.SEARCH)
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(mood, color = if (state.selectedMood == mood) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = if (skin.layoutFamily == 3) 14.sp else 13.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(state.playlist) { idx, song ->
                BitchordSongRowItem(
                    song = song,
                    isCurrent = idx == state.currentIndex,
                    onClick = { vm.playSongFromShelf(song, state.playlist) },
                    vm = vm
                )
            }
        }
    }
}

@Composable
fun SearchScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val skin = state.activeSkin
    val accent = skin.highlight
    var selectedSearchTab by remember { mutableIntStateOf(0) }

    if (!state.isLoggedIn) {
        SignInRequiredContent(vm, skin)
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(primaryBackdrop(skin)).padding(top = 16.dp)) {
        Text(when (skin.layoutFamily) { 0 -> "Find your frequency"; 1 -> "Search"; 2 -> "Search YouTube Music"; 3 -> "What do you want to hear?"; else -> "Search the sound" }, color = Color.White, fontSize = if (skin.layoutFamily == 3) 30.sp else 27.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { vm.onSearchQueryChanged(it) },
            placeholder = { Text("Search songs, artists, albums, playlists...", color = Color.Gray) },
            trailingIcon = {
                if (state.isSearching) {
                    CircularProgressIndicator(color = accent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.White)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            singleLine = true,
            shape = primaryCardShape(skin),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = accent,
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f)
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Songs", "Artists", "Community playlists").forEachIndexed { index, title ->
                val selected = selectedSearchTab == index
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) accent else primaryPanelColor(skin))
                        .clickable { selectedSearchTab = index }
                        .padding(horizontal = 15.dp, vertical = 9.dp)
                ) {
                    Text(
                        when (index) {
                            1 -> if (state.artistSearchResults.isNotEmpty()) "$title (${state.artistSearchResults.size})" else title
                            2 -> if (state.communityPlaylistResults.isNotEmpty()) "$title (${state.communityPlaylistResults.size})" else title
                            else -> title
                        },
                        color = if (selected) Color.Black else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val displayList = if (state.searchQuery.isBlank()) state.playlist else state.searchResults

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (selectedSearchTab == 0) {
                if (state.artistSearchResults.isNotEmpty()) {
                    item { SettingsSectionHeader("ARTISTS") }
                    itemsIndexed(state.artistSearchResults.take(5), key = { _, artist -> "search-artist-${artist.id}" }) { _, artist ->
                        ArtistSearchRow(artist) { vm.openArtist(artist) }
                    }
                    item { SettingsSectionHeader("SONGS") }
                }
                itemsIndexed(displayList) { _, song ->
                    BitchordSongRowItem(
                        song = song,
                        isCurrent = state.activeSong.id == song.id,
                        onClick = { vm.playSongFromShelf(song, displayList) },
                        vm = vm
                    )
                }
            } else if (selectedSearchTab == 1) {
                itemsIndexed(state.artistSearchResults, key = { _, artist -> artist.id }) { _, artist ->
                    ArtistSearchRow(artist) { vm.openArtist(artist) }
                }
            } else {
                itemsIndexed(state.communityPlaylistResults, key = { _, playlist -> playlist.id }) { _, playlist ->
                    CommunityPlaylistSearchRow(playlist) {
                        vm.playSongFromShelf(playlist, listOf(playlist))
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistSearchRow(artist: ArtistItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = artist.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(58.dp).clip(CircleShape)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist.subtitle.ifBlank { "Artist" }, color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = "Open artist", tint = Color.White.copy(alpha = 0.75f))
    }
}

@Composable
fun ArtistDetailScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val artist = state.activeArtist
    val accent = state.activeSkin.highlight
    Column(Modifier.fillMaxSize().background(primaryBackdrop(state.activeSkin))) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::handleSystemBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back to search", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text("ARTIST", color = accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                Text(artist?.name ?: "Artist", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.isSearching) CircularProgressIndicator(color = accent, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        if (artist == null) return@Column
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = artist.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(82.dp).clip(CircleShape))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(artist.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(artist.subtitle.ifBlank { "Albums and songs" }, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (state.artistAlbums.isNotEmpty()) {
                item { SettingsSectionHeader("ALBUMS & SINGLES") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(state.artistAlbums, key = { _, album -> album.albumBrowseId }) { _, album ->
                            Column(Modifier.width(132.dp).clickable { vm.openArtistAlbum(album) }) {
                                AsyncImage(model = album.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(132.dp).clip(RoundedCornerShape(14.dp)))
                                Spacer(Modifier.height(6.dp))
                                Text(album.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(album.artist, color = Color.White.copy(alpha = 0.58f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.selectedArtistAlbumTitle ?: "Songs", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (state.selectedArtistAlbumTitle != null) TextButton(onClick = vm::showAllArtistSongs) { Text("All songs", color = accent) }
                }
            }
            itemsIndexed(state.artistSongs, key = { _, song -> song.id }) { _, song ->
                BitchordSongRowItem(song, state.activeSong.id == song.id, onClick = { vm.playSongFromShelf(song, state.artistSongs) }, vm = vm)
            }
            if (!state.isSearching && state.artistAlbums.isEmpty() && state.artistSongs.isEmpty()) {
                item { Text("No artist catalog was returned. Try again or check your connection.", color = Color.White.copy(alpha = 0.62f), modifier = Modifier.padding(20.dp)) }
            }
        }
    }
}

@Composable
private fun CommunityPlaylistSearchRow(playlist: SongItemClean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = playlist.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(playlist.artist.ifBlank { "Community playlist" }, color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.PlayArrow, contentDescription = "Play playlist", tint = Color.White)
    }
}

@Composable
fun LibraryScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val skin = state.activeSkin
    val accent = skin.highlight
    val allSongs = (state.playlist + state.quickPicks + Favorites.songs() + state.downloadedSongs).distinctBy { it.id }
    val favorites = Favorites.songs()
    val downloaded = state.downloadedSongs

    Column(modifier = Modifier.fillMaxSize().background(primaryBackdrop(skin)).padding(top = 16.dp)) {
        Text(when (skin.layoutFamily) { 0 -> "Your collection"; 1 -> "Your Library"; 2 -> "Library"; 3 -> "Your music"; else -> "My collection" }, color = Color.White, fontSize = if (skin.layoutFamily == 3) 30.sp else 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(modifier = Modifier.height(12.dp))

        val libTabs = listOf(
            "All (${allSongs.size})",
            "Favorites (${favorites.size})",
            "Offline (${downloaded.size})",
            "Playlists (${state.savedPlaylists.size})"
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(libTabs) { idx, tabName ->
                val active = state.libraryTab == idx
                Box(
                    modifier = Modifier
                        .clip(primaryCardShape(skin))
                        .background(if (active) accent else primaryPanelColor(skin))
                        .clickable { vm.setLibraryTab(idx) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(tabName, color = if (active) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val displayedSongs = when (state.libraryTab) {
            1 -> favorites
            2 -> downloaded
            3 -> emptyList()
            else -> allSongs
        }

        if ((state.libraryTab == 3 && state.savedPlaylists.isEmpty()) || (state.libraryTab != 3 && displayedSongs.isEmpty())) {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (state.libraryTab == 3) "No playlists yet. Use any track's menu to create one." else "No tracks here yet.",
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        } else if (state.libraryTab == 3) {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                state.savedPlaylists.forEach { savedPlaylist ->
                    item(key = "saved_playlist_${savedPlaylist.id}") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(savedPlaylist.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text("${savedPlaylist.songs.size} songs", color = Color.Gray, fontSize = 12.sp)
                            }
                            IconButton(onClick = { savedPlaylist.songs.firstOrNull()?.let { vm.playSongFromShelf(it, savedPlaylist.songs) } }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = accent)
                            }
                        }
                    }
                    itemsIndexed(savedPlaylist.songs, key = { _, song -> "${savedPlaylist.id}_${song.id}" }) { _, song ->
                        BitchordSongRowItem(
                            song = song,
                            isCurrent = state.activeSong.id == song.id,
                            onClick = { vm.playSongFromShelf(song, savedPlaylist.songs) },
                            vm = vm
                        )
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(displayedSongs) { _, song ->
                    BitchordSongRowItem(
                        song = song,
                        isCurrent = state.activeSong.id == song.id,
                        onClick = { vm.playSongFromShelf(song, displayedSongs) },
                        vm = vm
                    )
                }
            }
        }
    }
}

@Composable
fun OfflineMusicScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val downloadedTracks = state.downloadedSongs

    Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Offline Vault", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.scanDownloadedFolder() }, enabled = !state.isScanningDownloads) {
                if (state.isScanningDownloads) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = "Scan music folder", tint = state.activeSkin.highlight)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.downloadsInProgress.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text("Active Downloads", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        state.downloadsInProgress.values.forEach { task ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(task.song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                val p = (task.progress ?: 0f).coerceIn(0f, 1f)
                                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = state.activeSkin.highlight)
                                Text("${(p * 100).toInt()}%", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            if (downloadedTracks.isEmpty() && state.downloadsInProgress.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No offline songs saved yet. Open any track's menu and tap Download.",
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            itemsIndexed(downloadedTracks) { _, song ->
                BitchordSongRowItem(
                    song = song,
                    isCurrent = state.activeSong.id == song.id,
                    onClick = { vm.playSongFromShelf(song, downloadedTracks) },
                    vm = vm
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BitChordWebViewLoginDialog(
    onLoginSuccess: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF07070B)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF141419))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Sign in to YouTube Music", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("VYBE Direct Session Sync", color = Color(0xFFFF7043), fontSize = 11.sp)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Color.White)
                    }
                }

                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    val cookies = cookieManager.getCookie("https://music.youtube.com") ?: ""
                                    if (cookies.contains("SAPISID")) {
                                        cookieManager.flush()
                                        onLoginSuccess(cookies)
                                    }
                                }
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                            }
                            loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F")
                        }
                    }
                )
            }
        }
    }
}

private fun musicRegionOptions(): List<Pair<String, String>> =
    (listOf("AUTO" to "Auto (device region)") + java.util.Locale.getISOCountries().mapNotNull { code ->
        java.util.Locale.Builder().setRegion(code).build()
            .getDisplayCountry(java.util.Locale.getDefault())
            .takeIf(String::isNotBlank)?.let { code to it }
    }).sortedWith(compareBy<Pair<String, String>> { it.first != "AUTO" }.thenBy { it.second.lowercase() })
@Composable
fun BitChordSetupWizard(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var setupArtistQuery by remember { mutableStateOf("") }
    val audioPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
    val notifPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else ""
    }
    var audioPermissionGranted by remember {
        mutableStateOf(context.checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED)
    }
    var notifPermissionGranted by remember {
        mutableStateOf(
            notifPermission.isEmpty() || context.checkSelfPermission(notifPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionActivity = context as? ComponentActivity
    DisposableEffect(permissionActivity, audioPermission, notifPermission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                audioPermissionGranted = permissionActivity?.checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED
                if (notifPermission.isNotEmpty()) {
                    notifPermissionGranted = permissionActivity?.checkSelfPermission(notifPermission) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        permissionActivity?.lifecycle?.addObserver(observer)
        onDispose { permissionActivity?.lifecycle?.removeObserver(observer) }
    }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioPermissionGranted = granted
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifPermissionGranted = granted
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        vm.setDownloadDirectory(it)
    }
    var wizardRegionMenuExpanded by remember { mutableStateOf(false) }
    val selectedFolder = state.downloadDirectoryUri?.let { value ->
        runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(value)).substringAfterLast(':') }.getOrNull()
    }?.takeIf { it.isNotBlank() } ?: "No folder selected yet"

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0B10)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BitchordWaveLogo(modifier = Modifier.size(28.dp))
                    Text("VYBE SETUP", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                }
                Text("Step ${state.setupStep + 1}/11", color = state.activeSkin.highlight, fontWeight = FontWeight.Bold)
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                when (state.setupStep) {
                    0 -> WizardStepContent("1. Permissions & Notifications", "Allow audio access, live playback notifications (with Pause & Close controls), and choose a download folder.") {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = state.displayName,
                                onValueChange = vm::setDisplayName,
                                label = { Text("Your name") },
                                singleLine = true
                            )
                            Button(
                                onClick = { audioLauncher.launch(audioPermission) },
                                colors = ButtonDefaults.buttonColors(containerColor = state.activeSkin.highlight),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (audioPermissionGranted) "✓ Audio Permission Granted" else "Grant Audio Permission",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (notifPermission.isNotEmpty()) {
                                Button(
                                    onClick = { notifLauncher.launch(notifPermission) },
                                    colors = ButtonDefaults.buttonColors(containerColor = state.activeSkin.highlight),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (notifPermissionGranted) "✓ Notification Permission Granted" else "Allow Playback Notifications",
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Folder, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Choose Download Folder")
                            }
                            Text(selectedFolder, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                    }
                    1 -> WizardStepContent("2. Connect YouTube Music", "Browse public music without signing in. Connect an account for your personal mixes, library and playlists.") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { vm.openWebLogin() },
                                colors = ButtonDefaults.buttonColors(containerColor = state.activeSkin.highlight),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (state.isLoggedIn) "✓ Connected to YouTube Music" else "Sign in to YouTube Music", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                            Text("Music country / region", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Box {
                                OutlinedButton(onClick = { wizardRegionMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(musicRegionOptions().firstOrNull { it.first == state.musicRegionCode }?.second ?: state.musicRegionCode, color = Color.White)
                                }
                                DropdownMenu(expanded = wizardRegionMenuExpanded, onDismissRequest = { wizardRegionMenuExpanded = false }) {
                                    musicRegionOptions().forEach { (code, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = { wizardRegionMenuExpanded = false; vm.setMusicRegion(code) },
                                            trailingIcon = if (state.musicRegionCode == code) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null
                                        )
                                    }
                                }
                            }
                            Text("Auto follows your device locale. You can change this any time in Settings.", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                        }
                    }
                    2 -> WizardStepContent("3. Instant Parallel Streaming", "FastStream races ANDROID_VR and iOS InnerTube clients simultaneously so tracks start in under a second.") {
                        Text("Parallel Stream Engine + 256MB Byte Prefetch Active", color = state.activeSkin.highlight, fontWeight = FontWeight.Bold)
                    }
                    3 -> WizardStepContent("4. Choose your app layout", "Pick one of 15 complete looks. You can change it any time in Settings.") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 250.dp).verticalScroll(rememberScrollState())) {
                            SkinTheme.values().toList().chunked(3).forEach { rowSkins ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    rowSkins.forEach { skin ->
                                        val selected = state.activeSkin == skin
                                        Column(
                                            modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                                                .clip(primaryCardShape(skin))
                                                .background(if (selected) skin.highlight.copy(alpha = 0.24f) else Color(0xFF16161C))
                                                .border(1.dp, if (selected) skin.highlight else Color.White.copy(alpha = 0.07f), primaryCardShape(skin))
                                                .clickable { vm.setSkin(skin) }.padding(horizontal = 7.dp, vertical = 6.dp),
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(skin.title, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(if (selected) "Selected" else "${skin.title} look", color = skin.highlight.copy(alpha = 0.85f), fontSize = 7.sp, maxLines = 1)
                                        }
                                    }
                                    repeat(3 - rowSkins.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    4 -> WizardStepContent("5. Android Auto Dashboard", "Select which shelves appear on your car screen.") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (menu in AUTO_MENU_TABS) {
                                val active = state.selectedAutoMenus.contains(menu)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF16161C))
                                        .clickable { vm.toggleAutoMenu(menu) }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(menu, color = Color.White)
                                    Checkbox(checked = active, onCheckedChange = { vm.toggleAutoMenu(menu) })
                                }
                            }
                        }
                    }
                    5 -> WizardStepContent("6. Local-First Privacy", "All cached streams, favorites, and offline songs stay strictly on your phone.") {
                        Text("Zero telemetry • Direct InnerTube streams.", color = Color.White.copy(0.7f))
                    }
                    6 -> WizardStepContent("7. Personalize your listening", "Choose genres, artists and moods next. You can change these later in Settings.") {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = state.activeSkin.highlight, modifier = Modifier.size(64.dp))
                    }
                    7 -> WizardStepContent("8. Choose your genres", "Pick the styles you want KILO to use for your Home shelves and recommendations.") {
                        ProfileChoiceGrid(PERSONALIZATION_GENRES, state.listenerProfile.favoriteGenres, vm::toggleProfileGenre)
                    }
                    8 -> WizardStepContent("9. Follow artists", "Browse popular worldwide and regional artists, or search YouTube Music.") {
                        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            ProfileArtistPicker(vm, state, setupArtistQuery) { setupArtistQuery = it }
                        }
                    }
                    9 -> WizardStepContent("10. Pick your moods", "Choose moods for Home, mixes and Android Auto.") {
                        ProfileChoiceGrid(PERSONALIZATION_MOODS, state.listenerProfile.favoriteMoods, vm::toggleProfileMood)
                    }
                    10 -> WizardStepContent("11. Tune your listening", "These preferences shape recommendations and sound on this device.") {
                        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            ProfileSingleChoice("Discovery", listOf("Conservative", "Balanced", "Explorer", "Adventurer"), state.listenerProfile.discoveryLevel) { vm.setProfileChoices(discovery = it) }
                            ProfileSingleChoice("Audio preference", listOf("Balanced", "Bass Lover", "Audiophile", "Vocal Focus", "Podcast", "Custom"), state.listenerProfile.audioPreference) { vm.setProfileChoices(audio = it) }
                            ProfileSingleChoice("Android Auto", listOf("Often", "Sometimes", "Never"), state.listenerProfile.androidAutoUsage) { vm.setProfileChoices(androidAuto = it) }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.setupStep > 0) {
                    OutlinedButton(
                        onClick = { vm.completeSetupStep(state.setupStep - 1) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("Back", fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = { vm.completeSetupStep(state.setupStep + 1) },
                    modifier = Modifier.weight(if (state.setupStep > 0) 1f else 2f),
                    colors = ButtonDefaults.buttonColors(containerColor = state.activeSkin.highlight)
                ) {
                    Text(if (state.setupStep == 10) "Launch KILO" else "Continue", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
fun WizardStepContent(title: String, desc: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text(desc, color = Color.White.copy(0.7f), fontSize = 14.sp)
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

fun formatMillis(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
