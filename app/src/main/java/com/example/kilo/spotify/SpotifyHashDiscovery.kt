package com.example.kilo.spotify

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class SpotifyHashDiscovery {

    companion object {
        private const val WEB_PLAYER_URL = "https://open.spotify.com/"
        private const val WEB_PLAYER_BUNDLE_PATTERN = "static/media/.*\\.js"
    }

    private val hashPatterns = listOf(
        Pattern.compile("\"operationName\":\"(\\w+)\".*?\"sha256Hash\":\"([a-f0-9]{64})\""),
        Pattern.compile("operationName:\"(\\w+)\".*?sha256Hash:\"([a-f0-9]{64})\""),
        Pattern.compile("persistedQuery.*?sha256Hash[:\"\\s]+([a-f0-9]{64})")
    )

    suspend fun discoverHashesFromWebPlayer(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        val discoveredHashes = mutableMapOf<String, String>()

        try {
            // Fetch main page to find JS bundle URLs
            val mainPageHtml = fetchUrl(WEB_PLAYER_URL)
            val bundleUrls = extractBundleUrls(mainPageHtml)

            // Fetch and parse each bundle
            for (bundleUrl in bundleUrls.take(5)) { // Limit to first 5 bundles
                try {
                    val bundleContent = fetchUrl(bundleUrl)
                    val hashes = extractHashesFromBundle(bundleContent)
                    discoveredHashes.putAll(hashes)
                } catch (e: Exception) {
                    // Continue with other bundles
                }
            }
        } catch (e: Exception) {
            // Return empty if discovery fails
        }

        return@withContext discoveredHashes
    }

    private fun fetchUrl(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun extractBundleUrls(html: String): List<String> {
        val urls = mutableListOf<String>()
        val pattern = Pattern.compile("src=\"(https://[^\"]*\\.js)\"")
        val matcher = pattern.matcher(html)

        while (matcher.find()) {
            val url = matcher.group(1)
            if (url.contains("spotify") || url.contains("cdn")) {
                urls.add(url)
            }
        }

        return urls.distinct()
    }

    private fun extractHashesFromBundle(content: String): Map<String, String> {
        val hashes = mutableMapOf<String, String>()

        for (pattern in hashPatterns) {
            val matcher = pattern.matcher(content)
            while (matcher.find()) {
                val operationName = matcher.group(1)
                val hash = matcher.group(2)
                if (operationName.isNotBlank() && hash.length == 64) {
                    hashes[operationName] = hash
                }
            }
        }

        return hashes
    }

    suspend fun discoverHashForOperation(context: Context, operationName: String): String? = withContext(Dispatchers.IO) {
        val allHashes = discoverHashesFromWebPlayer(context)
        allHashes[operationName]
    }
}