package com.example.kilo

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GitHubReleaseInfo(
    val tagName: String,
    val title: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val isUpdateAvailable: Boolean
)

object GitHubUpdateChecker {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/KineticFusion/Kilo-An-Android-Auto-Music-Player/releases/latest"

    fun check(installedVersion: String): GitHubReleaseInfo {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Kilo-Android-Update-Checker")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("GitHub returned HTTP $code")
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = release.optString("tag_name").takeIf(String::isNotBlank)
                ?: throw IllegalStateException("The latest GitHub release has no version tag.")
            val assets = release.optJSONArray("assets")
            val apkUrl = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")?.takeIf(String::isNotBlank)
            return GitHubReleaseInfo(
                tagName = tag,
                title = release.optString("name").ifBlank { tag },
                releaseUrl = release.optString("html_url"),
                apkUrl = apkUrl,
                isUpdateAvailable = compareVersions(tag, installedVersion) > 0
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun compareVersions(first: String, second: String): Int {
        fun parts(version: String) = Regex("\\d+").findAll(version).map { it.value.toLongOrNull() ?: 0L }.toList()
        val a = parts(first)
        val b = parts(second)
        for (index in 0 until maxOf(a.size, b.size)) {
            val comparison = (a.getOrElse(index) { 0L }).compareTo(b.getOrElse(index) { 0L })
            if (comparison != 0) return comparison
        }
        return 0
    }
}
