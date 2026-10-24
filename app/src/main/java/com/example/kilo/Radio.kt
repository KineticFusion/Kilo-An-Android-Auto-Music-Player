package com.example.kilo

import android.net.Uri
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class RadioStation(
    val id: String,
    val name: String,
    val streamUrl: String,
    val country: String = "",
    val language: String = "",
    val codec: String = "",
    val bitrateKbps: Int = 0,
    val favicon: String = ""
) {
    fun asSong(): SongItemClean = SongItemClean(
        id = "radio:$id",
        title = name,
        artist = listOf(country, language).filter(String::isNotBlank).joinToString(" • ").ifBlank { "Live radio" },
        album = "Internet Radio",
        coverUrl = favicon,
        audioUrl = streamUrl,
        durationMs = 0L,
        codecInfo = listOf(codec, bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" })
            .filterNotNull().filter(String::isNotBlank).joinToString(" • ").ifBlank { "Live stream" }
    )
}

object RadioStationStore {
    private const val PREFS = "vybe_app_settings"

    fun load(context: Context, key: String): List<RadioStation> = runCatching {
        val rows = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "[]"))
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = row.optString("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val url = row.optString("url").takeIf { it.startsWith("https://", true) } ?: return@mapNotNull null
            RadioStation(id, name, url, row.optString("country"), row.optString("language"), row.optString("codec"), row.optInt("bitrate"), row.optString("favicon"))
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, key: String, stations: List<RadioStation>) {
        val rows = JSONArray()
        stations.distinctBy { it.id }.forEach { station ->
            rows.put(org.json.JSONObject().apply {
                put("id", station.id); put("name", station.name); put("url", station.streamUrl)
                put("country", station.country); put("language", station.language); put("codec", station.codec)
                put("bitrate", station.bitrateKbps); put("favicon", station.favicon)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, rows.toString()).apply()
    }

    fun loadLast(context: Context): RadioStation? = load(context, "radio_last_station").firstOrNull()

    fun recordPlay(context: Context, station: RadioStation): List<RadioStation> {
        save(context, "radio_last_station", listOf(station))
        val recent = (listOf(station) + load(context, "radio_recent_stations").filterNot { it.id == station.id }).take(30)
        save(context, "radio_recent_stations", recent)
        return recent
    }

    fun customStation(name: String, url: String): RadioStation? {
        val cleaned = url.trim()
        if (!cleaned.startsWith("https://", ignoreCase = true)) return null
        val cleanedName = name.trim().ifBlank { "My station" }.take(80)
        return RadioStation("custom:${UUID.randomUUID()}", cleanedName, cleaned, codec = cleaned.substringBefore('?').substringAfterLast('.', "").uppercase())
    }
}

object RadioBrowser {
    private const val API = "https://de1.api.radio-browser.info/json/stations/search"

    fun stations(query: String = "", searchType: String = "name"): List<RadioStation> {
        val params = buildList {
            when (searchType) {
                "country" -> if (query.isNotBlank()) add("country=${Uri.encode(query.trim())}")
                "language" -> if (query.isNotBlank()) add("language=${Uri.encode(query.trim())}")
                "genre" -> if (query.isNotBlank()) add("tag=${Uri.encode(query.trim())}")
                else -> if (query.isNotBlank()) add("name=${Uri.encode(query.trim())}")
            }
            if (query.isBlank()) add("countrycode=IN")
            add("hidebroken=true")
            add("is_https=true")
            add("order=${if (query.isBlank()) "clickcount" else "votes"}")
            add("reverse=true")
            add("limit=40")
        }.joinToString("&")
        val connection = (URL("$API?$params").openConnection() as HttpURLConnection).apply {
            connectTimeout = 4500
            readTimeout = 6000
            setRequestProperty("User-Agent", "Kilo/1.1 Android Internet Radio")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) return emptyList()
            val rows = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            buildList {
                for (i in 0 until rows.length()) {
                    val item = rows.optJSONObject(i) ?: continue
                    val id = item.optString("stationuuid").takeIf(String::isNotBlank) ?: continue
                    val name = item.optString("name").trim().takeIf(String::isNotBlank) ?: continue
                    val streamUrl = item.optString("url_resolved").ifBlank { item.optString("url") }
                    if (!streamUrl.startsWith("https://", ignoreCase = true)) continue
                    add(
                        RadioStation(
                            id = id,
                            name = name,
                            streamUrl = streamUrl,
                            country = item.optString("country").trim(),
                            language = item.optString("language").split(",").firstOrNull().orEmpty().trim(),
                            codec = item.optString("codec").uppercase(),
                            bitrateKbps = item.optInt("bitrate").takeIf { it > 0 } ?: 0,
                            favicon = item.optString("favicon").takeIf { it.startsWith("https://", ignoreCase = true) }.orEmpty()
                        )
                    )
                }
            }.distinctBy { it.id }
        } finally {
            connection.disconnect()
        }
    }
}

@Composable
fun RadioScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val accent = state.activeSkin.highlight
    var query by remember { mutableStateOf("") }
    var searchType by remember { mutableStateOf("name") }
    var listTab by remember { mutableStateOf("Browse") }
    var stations by remember { mutableStateOf(emptyList<RadioStation>()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var dialIndex by remember { mutableStateOf(0f) }
    var showAddStation by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var sleepClockMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    LaunchedEffect(state.sleepTimerEndsAtMs) {
        while (state.sleepTimerEndsAtMs != null) {
            sleepClockMs = SystemClock.elapsedRealtime()
            delay(30_000L)
        }
    }
    val sleepMinutesRemaining = state.sleepTimerEndsAtMs?.let { deadline ->
        ((deadline - sleepClockMs).coerceAtLeast(0L) + 59_999L) / 60_000L
    }

    val visibleStations = when (listTab) {
        "Favorites" -> state.radioFavorites
        "Recent" -> state.radioRecentStations
        "My stations" -> state.customRadioStations
        else -> stations
    }.distinctBy { it.id }

    fun tune(index: Int) {
        if (visibleStations.isEmpty()) return
        val nextIndex = index.coerceIn(0, visibleStations.lastIndex)
        dialIndex = nextIndex.toFloat()
        vm.playRadioStation(visibleStations[nextIndex])
    }

    LaunchedEffect(query, searchType, listTab) {
        if (listTab != "Browse") return@LaunchedEffect
        delay(if (query.isBlank()) 0 else 350)
        loading = true
        failed = false
        val result = try {
            Result.success(withContext(Dispatchers.IO) { RadioBrowser.stations(query, searchType) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        stations = result.getOrDefault(emptyList())
        failed = result.isFailure
        loading = false
    }

    LaunchedEffect(visibleStations, state.activeSong.id) {
        val playingIndex = visibleStations.indexOfFirst { "radio:${it.id}" == state.activeSong.id }
        if (playingIndex >= 0) dialIndex = playingIndex.toFloat()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Radio, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Text("Radio", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Discover, save and tune live stations", color = Color.LightGray, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showAddStation = true }) { Icon(Icons.Default.Add, contentDescription = "Add station", tint = accent) }
        }
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Browse", "Favorites", "Recent", "My stations").forEach { tab ->
                item { FilterChip(selected = listTab == tab, onClick = { listTab = tab }, label = { Text(tab, maxLines = 1) }) }
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(if (listTab == "Browse") "Search by ${searchType}s" else "Filter ${listTab.lowercase()}") },
            shape = RoundedCornerShape(18.dp)
        )
        if (listTab == "Browse") {
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("name" to "Station", "genre" to "Genre", "country" to "Country", "language" to "Language").forEach { (value, label) ->
                    item { FilterChip(selected = searchType == value, onClick = { searchType = value }, label = { Text(label, fontSize = 11.sp) }) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = vm::playLastRadioStation, enabled = state.lastRadioStation != null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.History, contentDescription = null, tint = accent)
                Text("Play last", color = Color.White)
            }
            TextButton(onClick = { vm.playRandomRadioStation(visibleStations) }, enabled = visibleStations.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Shuffle, contentDescription = null, tint = accent)
                Text("Random", color = Color.White)
            }
            TextButton(onClick = { showSleepTimer = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Timer, contentDescription = null, tint = accent)
                Text(
                    when {
                        state.sleepTimerTrackId != null -> "After track"
                        sleepMinutesRemaining != null -> "${sleepMinutesRemaining}m left"
                        else -> "Sleep"
                    },
                    color = Color.White
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading && listTab == "Browse" -> CircularProgressIndicator(color = accent, modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
            failed && listTab == "Browse" -> Text("Stations could not load. Check your connection and try again.", color = Color.LightGray, modifier = Modifier.padding(12.dp))
            visibleStations.isEmpty() -> Text(
                when (listTab) { "Favorites" -> "Favorite stations will appear here."; "Recent" -> "Stations you play will appear here."; "My stations" -> "Use + to add a station URL."; else -> "No stations found. Try another search." },
                color = Color.LightGray, modifier = Modifier.padding(12.dp)
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    val activeIndex = dialIndex.toInt().coerceIn(0, visibleStations.lastIndex)
                    val activeStation = visibleStations[activeIndex]
                    val frequency = 87.5f + activeIndex * 0.5f
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF191B22)),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("VIRTUAL FM TUNER", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text("${"%.1f".format(java.util.Locale.US, frequency)}", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                            Text("MHz  •  ${activeStation.name}", color = Color.LightGray, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (visibleStations.size > 1) {
                                Slider(
                                    value = dialIndex.coerceIn(0f, visibleStations.lastIndex.toFloat()),
                                    onValueChange = { dialIndex = it },
                                    onValueChangeFinished = { tune(dialIndex.toInt()) },
                                    valueRange = 0f..visibleStations.lastIndex.toFloat(),
                                    steps = (visibleStations.size - 2).coerceAtLeast(0)
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("87.5", color = Color.Gray, fontSize = 11.sp)
                                Text("${"%.1f".format(java.util.Locale.US, 87.5f + visibleStations.lastIndex * 0.5f)}", color = Color.Gray, fontSize = 11.sp)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { tune((dialIndex.toInt() - 1).coerceAtLeast(0)) }) { Text("◀ SEEK") }
                                Button(onClick = { tune(dialIndex.toInt()) }) { Text("TUNE") }
                                Button(onClick = { tune((dialIndex.toInt() + 1).coerceAtMost(visibleStations.lastIndex)) }) { Text("SEEK ▶") }
                            }
                            TextButton(onClick = { vm.toggleRadioRecording(activeStation) }) {
                                Text(if (state.isRadioRecording) "Stop recording" else "Record this station", color = if (state.isRadioRecording) Color(0xFFFF6B6B) else accent, fontWeight = FontWeight.Bold)
                            }
                            Text("Recording reconnects to the stream and uses extra data.", color = Color.Gray, fontSize = 10.sp, textAlign = TextAlign.Center)
                            Text("${activeStation.country.ifBlank { "Internet station" }}  •  ${activeStation.codec.ifBlank { "Live stream" }}", color = Color.Gray, fontSize = 11.sp, textAlign = TextAlign.Center)
                        }
                    }
                    Text(listTab.uppercase(), color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                }
                items(visibleStations, key = { it.id }) { station ->
                    val stationIndex = visibleStations.indexOf(station)
                    val stationFrequency = 87.5f + stationIndex * 0.5f
                    val isFavorite = state.radioFavorites.any { it.id == station.id }
                    Card(
                        onClick = { tune(stationIndex) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF191B22)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = state.activeSkin.highlight, modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("${"%.1f".format(java.util.Locale.US, stationFrequency)}  ${station.name}", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val details = listOf(station.country, station.language, station.codec, station.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" })
                                    .filterNotNull().filter(String::isNotBlank).joinToString(" • ")
                                Text(details.ifBlank { "Live station" }, color = Color.LightGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { vm.toggleRadioFavorite(station) }) {
                                Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = if (isFavorite) "Remove favorite" else "Add favorite", tint = if (isFavorite) accent else Color.LightGray)
                            }
                            if (listTab == "My stations") {
                                IconButton(onClick = { vm.removeCustomRadioStation(station) }) { Icon(Icons.Default.Close, contentDescription = "Remove station", tint = Color.LightGray) }
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play ${station.name}", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddStation) AlertDialog(
        onDismissRequest = { showAddStation = false },
        title = { Text("Add a station") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = customName, onValueChange = { customName = it }, label = { Text("Station name") }, singleLine = true)
                OutlinedTextField(value = customUrl, onValueChange = { customUrl = it }, label = { Text("Direct stream URL") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.addCustomRadioStation(customName, customUrl); customName = ""; customUrl = ""; showAddStation = false }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = { showAddStation = false }) { Text("Cancel") } }
    )

    if (showSleepTimer) AlertDialog(
        onDismissRequest = { showSleepTimer = false },
        title = { Text("Sleep timer") },
        text = { Text("Pause playback after the selected time.") },
        confirmButton = {
            Row {
                listOf(15, 30, 45, 60).forEach { minutes ->
                    TextButton(onClick = { vm.setRadioSleepTimer(minutes); showSleepTimer = false }) { Text("${minutes}m") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.setRadioSleepTimer(null); showSleepTimer = false }) { Text("Cancel timer") }
        }
    )
}
