package com.example.kilo.updates

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Checks the single public GitHub release and exposes only its version name to the UI. */
data class UpdateRelease(
    val versionName: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val apkSizeBytes: Long,
    val sha256Digest: String?,
    val releaseNotes: String,
    val isUpdateAvailable: Boolean
)

object UpdateCenter {
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/KineticFusion/Kilo-An-Android-Auto-Music-Player/releases/latest"

    fun check(installedVersion: String): UpdateRelease {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7_000
            readTimeout = 7_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Kilo-Android-Update-Center")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) error("Update check returned HTTP $responseCode")
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = release.optString("tag_name").trim().removePrefix("v").ifBlank {
                error("The latest release has no version name")
            }
            val assets = release.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            val digest = apk?.optString("digest")?.removePrefix("sha256:")?.takeIf(String::isNotBlank)
            return UpdateRelease(
                versionName = version,
                releaseUrl = release.optString("html_url"),
                apkUrl = apk?.optString("browser_download_url")?.takeIf(String::isNotBlank),
                apkSizeBytes = apk?.optLong("size", 0L) ?: 0L,
                sha256Digest = digest,
                releaseNotes = release.optString("body"),
                isUpdateAvailable = compareVersions(version, installedVersion) > 0
            )
        } finally {
            connection.disconnect()
        }
    }

    fun compareVersions(first: String, second: String): Int {
        val a = Regex("\\d+").findAll(first).map { it.value.toLongOrNull() ?: 0L }.toList()
        val b = Regex("\\d+").findAll(second).map { it.value.toLongOrNull() ?: 0L }.toList()
        for (index in 0 until maxOf(a.size, b.size)) {
            val result = a.getOrElse(index) { 0L }.compareTo(b.getOrElse(index) { 0L })
            if (result != 0) return result
        }
        return 0
    }
}

enum class UpdateDownloadPhase { PENDING, RUNNING, PAUSED, COMPLETE, FAILED }

data class UpdateDownloadProgress(
    val phase: UpdateDownloadPhase,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Int,
    val errorCode: Int = 0
)

/** Uses Android's background download service, which continues on Wi-Fi or mobile data. */
class UpdateDownloader(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun enqueue(apkUrl: String, versionName: String): Long {
        val safeVersion = versionName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "update" }
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("KILO $safeVersion")
            .setDescription("Downloading update")
            .setMimeType("application/vnd.android.package-archive")
            .setAllowedOverMetered(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, "Kilo-$safeVersion.apk")
        return manager.enqueue(request)
    }

    fun query(downloadId: Long): UpdateDownloadProgress? = runCatching {
        manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val status = cursor.getIntOrDefault(DownloadManager.COLUMN_STATUS)
            val downloaded = cursor.getLongOrDefault(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val total = cursor.getLongOrDefault(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
            val phase = when (status) {
                DownloadManager.STATUS_PENDING -> UpdateDownloadPhase.PENDING
                DownloadManager.STATUS_RUNNING -> UpdateDownloadPhase.RUNNING
                DownloadManager.STATUS_PAUSED -> UpdateDownloadPhase.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> UpdateDownloadPhase.COMPLETE
                else -> UpdateDownloadPhase.FAILED
            }
            UpdateDownloadProgress(phase, downloaded, total, percent,
                if (phase == UpdateDownloadPhase.FAILED) cursor.getIntOrDefault(DownloadManager.COLUMN_REASON) else 0)
        }
    }.getOrNull()

    fun sha256(downloadId: Long): String? = runCatching {
        val uri = manager.getUriForDownloadedFile(downloadId) ?: return@runCatching null
        val digest = MessageDigest.getInstance("SHA-256")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: return@runCatching null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun remove(downloadId: Long) {
        manager.remove(downloadId)
    }

    fun downloadedUri(downloadId: Long): Uri? = manager.getUriForDownloadedFile(downloadId)

    private fun Cursor.getIntOrDefault(column: String): Int = getColumnIndex(column).takeIf { it >= 0 }?.let(::getInt) ?: 0
    private fun Cursor.getLongOrDefault(column: String): Long = getColumnIndex(column).takeIf { it >= 0 }?.let(::getLong) ?: 0L
}

object UpdateInstaller {
    fun open(context: Context, apkUri: Uri): Boolean {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
            runCatching {
                appContext.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false
        }
        return runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apkUri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            true
        }.getOrDefault(false)
    }
}

