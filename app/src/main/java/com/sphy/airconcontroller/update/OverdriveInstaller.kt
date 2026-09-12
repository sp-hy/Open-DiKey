package com.sphy.airconcontroller.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Launches OverDrive ([PACKAGE]) when installed, otherwise fetches the latest APK from
 * [https://github.com/sp-hy/Overdrive-release](https://github.com/sp-hy/Overdrive-release).
 */
object OverdriveInstaller {
    const val PACKAGE = "com.strike"
    const val REPO = "sp-hy/Overdrive-release"
    private const val USER_AGENT = "OpenDiKey-Overdrive"
    private const val APK_CACHE_NAME = "overdrive.apk"

    fun isInstalled(context: Context): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    fun launchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(PACKAGE)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun cacheFile(context: Context): File =
        File(File(context.cacheDir, "updates"), APK_CACHE_NAME)

    suspend fun fetchLatestRelease(): AppUpdater.LatestRelease = withContext(Dispatchers.IO) {
        val connection = (URL("https://api.github.com/repos/$REPO/releases?per_page=15")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            if (connection.responseCode != 200) {
                val err = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                error("GitHub API HTTP ${connection.responseCode}: ${err.take(200)}")
            }
            val releases = JSONArray(connection.inputStream.bufferedReader().readText())
            for (i in 0 until releases.length()) {
                val json = releases.getJSONObject(i)
                if (json.optBoolean("draft", false)) continue
                val apk = pickApkAsset(json.getJSONArray("assets")) ?: continue
                val tag = json.getString("tag_name")
                return@withContext AppUpdater.LatestRelease(
                    tag = tag,
                    versionName = AppUpdater.normalizeVersion(tag),
                    apkUrl = apk,
                    publishedAt = json.optString("published_at").ifBlank { null },
                )
            }
            error("No APK assets found in $REPO releases")
        } finally {
            connection.disconnect()
        }
    }

    /** Prefer a release/arm64 APK; fall back to any `.apk`. */
    private fun pickApkAsset(assets: JSONArray): String? {
        var fallback: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = asset.getString("browser_download_url")
            val lower = name.lowercase()
            if ("overdrive" in lower || "release" in lower || "arm64" in lower) {
                return url
            }
            if (fallback == null) fallback = url
        }
        return fallback
    }
}
