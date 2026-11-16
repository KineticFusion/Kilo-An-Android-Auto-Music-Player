package com.example.kilo.streaming

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The central Hybrid Orchestrator for KILO.
 * Searches all enabled sources (YouTube Music, Spotify Web Bridge, BitChord FLAC addons)
 * and resolves streams through the user's priority chain.
 */
object HybridEngine {
    private const val TAG = "KiloHybridEngine"

    interface LegacyYouTubeBridge {
        suspend fun searchSongs(query: String): List<HybridTrack>
        suspend fun resolveYouTubeStream(videoId: String): ResolvedStream?
    }

    @Volatile
    private var ytBridge: LegacyYouTubeBridge? = null

    private val activeLeaseCache = ConcurrentHashMap<String, ResolvedStream>()

    fun attachYouTubeBridge(bridge: LegacyYouTubeBridge) {
        ytBridge = bridge
    }

    suspend fun searchHybrid(
        context: Context,
        query: String,
        limitPerSource: Int = 15
    ): List<HybridTrack> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val appCtx = context.applicationContext
        HybridSourceRegistry.ensureInitialized(appCtx)

        val activeConfigs = HybridSourceRegistry.sources.value
            .filter { it.enabled }
            .sortedBy { it.priority }

        if (activeConfigs.isEmpty()) return@withContext emptyList()

        val resultsByPriority: List<List<HybridTrack>> = coroutineScope {
            activeConfigs.map { cfg ->
                async {
                    runCatching {
                        when (cfg.type) {
                            SourceType.YOUTUBE -> {
                                ytBridge?.searchSongs(cleanQuery)?.take(limitPerSource).orEmpty()
                            }
                            SourceType.SPOTIFY, SourceType.ADDON_MANIFEST -> {
                                HybridSourceRegistry.getProvider(cfg.id)
                                    ?.search(cleanQuery, limitPerSource)
                                    .orEmpty()
                            }
                            SourceType.LOCAL -> emptyList()
                        }
                    }.getOrElse { err ->
                        Log.w(TAG, "Hybrid search skipped for ${cfg.name}: ${err.message}")
                        emptyList()
                    }
                }
            }.awaitAll()
        }

        val seenSignatures = LinkedHashSet<String>()
        val merged = mutableListOf<HybridTrack>()

        for (sourceList in resultsByPriority) {
            for (track in sourceList) {
                val signature = "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
                if (seenSignatures.add(signature)) {
                    merged.add(track)
                }
            }
        }
        merged
    }

    suspend fun resolvePlayableStream(
        context: Context,
        canonicalId: String,
        title: String,
        artist: String,
        durationMs: Long,
        preferLossless: Boolean = true,
        forceRefresh: Boolean = false
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        HybridSourceRegistry.ensureInitialized(appCtx)

        if (forceRefresh) {
            activeLeaseCache.remove(canonicalId)
        } else {
            activeLeaseCache[canonicalId]
                ?.takeIf { it.expiresAtEpochMs > System.currentTimeMillis() + 15_000L }
                ?.let { return@withContext it }
        }

        val (sourcePrefix, rawId) = HybridTrack.parsePrefix(canonicalId)
        val orderedSources = HybridSourceRegistry.sources.value
            .filter { it.enabled }
            .sortedBy { it.priority }

        if (preferLossless) {
            for (cfg in orderedSources.filter { it.type == SourceType.ADDON_MANIFEST && it.supportsLossless }) {
                val provider = HybridSourceRegistry.getProvider(cfg.id) ?: continue
                if (forceRefresh) provider.invalidateTrack(canonicalId)
                val lease = provider.resolveStream(
                    trackId = canonicalId,
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    preferLossless = true
                )
                if (lease != null) {
                    activeLeaseCache[canonicalId] = lease
                    return@withContext lease
                }
            }
        }

        if (sourcePrefix != HybridSourceRegistry.SOURCE_ID_YOUTUBE &&
            sourcePrefix != HybridSourceRegistry.SOURCE_ID_SPOTIFY
        ) {
            HybridSourceRegistry.getProvider(sourcePrefix)?.let { nativeProvider ->
                if (forceRefresh) nativeProvider.invalidateTrack(canonicalId)
                val lease = nativeProvider.resolveStream(
                    trackId = canonicalId,
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    preferLossless = preferLossless
                )
                if (lease != null) {
                    activeLeaseCache[canonicalId] = lease
                    return@withContext lease
                }
            }
        }

        val ytVideoId = if (sourcePrefix == HybridSourceRegistry.SOURCE_ID_YOUTUBE && rawId.isNotBlank()) {
            rawId
        } else {
            val lookupQuery = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
            ytBridge?.searchSongs(lookupQuery)?.firstOrNull()?.rawId.orEmpty()
        }

        if (ytVideoId.isNotBlank()) {
            val ytLease = ytBridge?.resolveYouTubeStream(ytVideoId)
            if (ytLease != null) {
                activeLeaseCache[canonicalId] = ytLease
                return@withContext ytLease
            }
        }

        null
    }

    suspend fun fetchSpotifyHomeTracks(context: Context): List<HybridTrack> {
        val appCtx = context.applicationContext
        HybridSourceRegistry.ensureInitialized(appCtx)
        if (!HybridSourceRegistry.isSpotifyEnabled(appCtx)) return emptyList()
        val provider = HybridSourceRegistry.getProvider(HybridSourceRegistry.SOURCE_ID_SPOTIFY)
                as? SpotifyCatalogBridgeProvider ?: return emptyList()
        return provider.fetchHomeRecommendations(25)
    }

    suspend fun fetchSpotifyLikedTracks(context: Context): List<HybridTrack> {
        val appCtx = context.applicationContext
        HybridSourceRegistry.ensureInitialized(appCtx)
        if (!HybridSourceRegistry.isSpotifyEnabled(appCtx)) return emptyList()
        val provider = HybridSourceRegistry.getProvider(HybridSourceRegistry.SOURCE_ID_SPOTIFY)
                as? SpotifyCatalogBridgeProvider ?: return emptyList()
        return provider.fetchLikedTracks(50)
    }

    suspend fun fetchSpotifyUserPlaylists(context: Context): List<SpotifyPlaylistSummary> {
        val appCtx = context.applicationContext
        HybridSourceRegistry.ensureInitialized(appCtx)
        if (!HybridSourceRegistry.isSpotifyEnabled(appCtx)) return emptyList()
        val provider = HybridSourceRegistry.getProvider(HybridSourceRegistry.SOURCE_ID_SPOTIFY)
                as? SpotifyCatalogBridgeProvider ?: return emptyList()
        return provider.fetchUserPlaylists(30)
    }

    suspend fun fetchSpotifyPlaylistTracks(context: Context, playlistId: String): List<HybridTrack> {
        val appCtx = context.applicationContext
        HybridSourceRegistry.ensureInitialized(appCtx)
        val provider = HybridSourceRegistry.getProvider(HybridSourceRegistry.SOURCE_ID_SPOTIFY)
                as? SpotifyCatalogBridgeProvider ?: return emptyList()
        return provider.fetchPlaylistTracks(playlistId, 100)
    }

    fun invalidateStream(canonicalId: String) {
        activeLeaseCache.remove(canonicalId)
        val (prefix, _) = HybridTrack.parsePrefix(canonicalId)
        HybridSourceRegistry.getProvider(prefix)?.invalidateTrack(canonicalId)
    }
}