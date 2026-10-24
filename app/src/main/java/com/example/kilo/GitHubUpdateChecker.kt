package com.example.kilo

import com.example.kilo.updates.UpdateCenter

data class GitHubReleaseInfo(
    val tagName: String,
    val title: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val isUpdateAvailable: Boolean,
    val apkSizeBytes: Long = 0L,
    val sha256Digest: String? = null,
    val notes: String = ""
)

/** App-facing adapter for KILO's single-release update center. */
object GitHubUpdateChecker {
    fun check(installedVersion: String): GitHubReleaseInfo {
        val release = UpdateCenter.check(installedVersion)
        return GitHubReleaseInfo(
            tagName = release.versionName,
            title = release.versionName,
            releaseUrl = release.releaseUrl,
            apkUrl = release.apkUrl,
            isUpdateAvailable = release.isUpdateAvailable,
            apkSizeBytes = release.apkSizeBytes,
            sha256Digest = release.sha256Digest,
            notes = release.releaseNotes
        )
    }
}
