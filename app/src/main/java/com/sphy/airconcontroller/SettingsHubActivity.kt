package com.sphy.airconcontroller

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import com.sphy.airconcontroller.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App settings hub. */
class SettingsHubActivity : OpenDiKeyActivity() {
    private lateinit var versionText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var checkButton: Button
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_hub)

        versionText = findViewById(R.id.settingsVersionText)
        statusText = findViewById(R.id.settingsUpdateStatus)
        progress = findViewById(R.id.settingsUpdateProgress)
        checkButton = findViewById(R.id.settingsCheckUpdateButton)

        findViewById<android.widget.ImageButton>(R.id.settingsBackButton).setOnClickListener {
            finish()
        }
        versionText.text = getString(
            R.string.settings_version_fmt,
            AppUpdater.currentVersionName(this),
        )
        checkButton.setOnClickListener { checkForUpdates() }
    }

    private fun checkForUpdates() {
        if (busy) return
        setBusy(true, getString(R.string.settings_updates_checking))
        lifecycleScope.launch {
            when (val result = AppUpdater.checkForUpdate(this@SettingsHubActivity)) {
                is AppUpdater.CheckResult.UpToDate -> {
                    setBusy(false, getString(R.string.settings_updates_up_to_date, result.latest))
                }
                is AppUpdater.CheckResult.Failed -> {
                    setBusy(false, getString(R.string.settings_updates_failed, result.message))
                }
                is AppUpdater.CheckResult.UpdateAvailable -> {
                    setBusy(false, getString(R.string.settings_updates_available, result.release.versionName))
                    promptInstall(result.release, result.current)
                }
            }
        }
    }

    private fun promptInstall(release: AppUpdater.LatestRelease, current: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_update_dialog_title)
            .setMessage(
                getString(
                    R.string.settings_update_dialog_body,
                    current,
                    release.versionName,
                ),
            )
            .setNegativeButton(R.string.settings_update_later, null)
            .setPositiveButton(R.string.settings_update_install) { _, _ ->
                downloadAndInstall(release)
            }
            .show()
    }

    private fun downloadAndInstall(release: AppUpdater.LatestRelease) {
        if (busy) return
        if (!AppUpdater.canInstallPackages(this)) {
            statusText.text = getString(R.string.settings_updates_need_permission)
            startActivity(AppUpdater.installPermissionSettingsIntent(this))
            return
        }

        setBusy(true, getString(R.string.settings_updates_downloading, 0))
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        progress.progress = 0

        lifecycleScope.launch {
            try {
                val dest = AppUpdater.updateCacheFile(this@SettingsHubActivity)
                AppUpdater.downloadApk(release.apkUrl, dest) { downloaded, total ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (total > 0L) {
                            progress.isIndeterminate = false
                            val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            progress.progress = pct
                            statusText.text = getString(R.string.settings_updates_downloading, pct)
                        } else {
                            progress.isIndeterminate = true
                            statusText.text = getString(
                                R.string.settings_updates_downloading_bytes,
                                downloaded / 1024L,
                            )
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    setBusy(false, getString(R.string.settings_updates_installing))
                    progress.visibility = View.GONE
                    startActivity(AppUpdater.installApkIntent(this@SettingsHubActivity, dest))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    setBusy(
                        false,
                        getString(
                            R.string.settings_updates_failed,
                            e.message ?: e.javaClass.simpleName,
                        ),
                    )
                    Toast.makeText(
                        this@SettingsHubActivity,
                        R.string.settings_updates_signature_hint,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun setBusy(value: Boolean, status: String) {
        busy = value
        checkButton.isEnabled = !value
        statusText.text = status
        if (!value) progress.visibility = View.GONE
    }
}
