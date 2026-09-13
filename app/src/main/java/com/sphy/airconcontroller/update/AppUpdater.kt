package com.sphy.airconcontroller.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases and downloads/installs `open-dikey.apk`. */
object AppUpdater {
    const val REPO = "sp-hy/Open-DiKey"
    const val APK_ASSET = "open-dikey.apk"
    private const val USER_AGENT = "OpenDiKey-Updater"

    data class LatestRelease(
        val tag: String,
        val versionName: String,
        val apkUrl: String,
        val publishedAt: String?,
    )

    sealed class CheckResult {
        data class UpToDate(val current: String, val latest: String) : CheckResult()
        data class UpdateAvailable(val release: LatestRelease, val current: String) : CheckResult()
        data class Failed(val message: String) : CheckResult()
    }

    fun currentVersionName(context: Context): String =
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

    suspend fun checkForUpdate(context: Context): CheckResult = withContext(Dispatchers.IO) {
        try {
            val release = fetchLatestRelease()
            val current = currentVersionName(context)
            if (isNewer(release.versionName, current)) {
                CheckResult.UpdateAvailable(release, current)
            } else {
                CheckResult.UpToDate(current, release.versionName)
            }
        } catch (e: Exception) {
            CheckResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun downloadApk(
        apkUrl: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("Download failed (HTTP ${connection.responseCode})")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            if (dest.length() < 1_000L) error("Downloaded APK looks empty")
            dest
        } finally {
            connection.disconnect()
        }
    }

    fun updateCacheFile(context: Context): File =
        File(File(context.cacheDir, "updates"), APK_ASSET)

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * Opens a settings screen for install permission when one exists.
     * Returns false on DiLink / devices that ship no matching activity.
     */
    fun openInstallPermissionSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return true
            }
        }
        return false
    }

    fun installApkIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // NEW_TASK breaks returning to the calling activity / activity results.
            if (context !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /** Tag / versionName form `vYYYY.MM.DD-HHMM` or `YYYY.MM.DD-HHMM`; date stamps compare lexicographically. */
    fun isNewer(latestVersionName: String, currentVersionName: String): Boolean {
        val latest = normalizeVersion(latestVersionName)
        val current = normalizeVersion(currentVersionName)
        if (latest.isEmpty()) return false
        if (current.isEmpty() || !current.first().isDigit()) return true
        return latest > current
    }

    fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V")

    private fun fetchLatestRelease(): LatestRelease {
        val connection = (URL("https://api.github.com/repos/$REPO/releases/latest")
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
            val body = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val tag = json.getString("tag_name")
            val versionName = normalizeVersion(tag)
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name") == APK_ASSET) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) {
                error("Release $tag has no $APK_ASSET asset")
            }
            return LatestRelease(
                tag = tag,
                versionName = versionName,
                apkUrl = apkUrl,
                publishedAt = json.optString("published_at").ifBlank { null },
            )
        } finally {
            connection.disconnect()
        }
    }
}
