package com.sphy.airconcontroller

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import com.sphy.airconcontroller.update.AppUpdater
import com.sphy.airconcontroller.update.OverdriveInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sentry info page: install when missing, open when present. */
class SentryActivity : OpenDiKeyActivity() {
    private lateinit var headline: TextView
    private lateinit var body: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var openButton: Button
    private lateinit var installButton: Button
    private var busy = false
    private var packageWatchJob: Job? = null

    private val installLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshInstalledState()
            watchForPackageChange()
        }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart ?: return
            if (pkg == OverdriveInstaller.PACKAGE) {
                refreshInstalledState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sentry)

        headline = findViewById(R.id.sentryHeadline)
        body = findViewById(R.id.sentryBody)
        statusText = findViewById(R.id.sentryInstallStatus)
        progress = findViewById(R.id.sentryInstallProgress)
        openButton = findViewById(R.id.sentryOpenButton)
        installButton = findViewById(R.id.sentryInstallButton)

        findViewById<android.widget.ImageButton>(R.id.sentryBackButton).setOnClickListener {
            finish()
        }
        openButton.setOnClickListener { openSentry() }
        installButton.setOnClickListener { downloadAndInstall() }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageChangeReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(packageChangeReceiver, filter)
        }
        refreshInstalledState()
    }

    override fun onResume() {
        super.onResume()
        refreshInstalledState()
    }

    override fun onStop() {
        packageWatchJob?.cancel()
        packageWatchJob = null
        try {
            unregisterReceiver(packageChangeReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
        super.onStop()
    }

    private fun refreshInstalledState() {
        val installed = OverdriveInstaller.isInstalled(this)
        openButton.visibility = if (installed) View.VISIBLE else View.GONE
        if (installed) {
            headline.setText(R.string.sentry_installed_title)
            body.setText(R.string.sentry_installed_body)
            installButton.setText(R.string.sentry_reinstall_button)
            if (!busy) {
                statusText.setText(R.string.sentry_installed_status)
            }
        } else {
            headline.setText(R.string.sentry_install_title)
            body.setText(R.string.sentry_install_body)
            installButton.setText(R.string.sentry_install_button)
            if (!busy) {
                statusText.setText(R.string.sentry_install_idle)
            }
        }
    }

    /** PackageManager can lag briefly after the installer returns. */
    private fun watchForPackageChange() {
        packageWatchJob?.cancel()
        packageWatchJob = lifecycleScope.launch {
            repeat(8) {
                delay(750)
                refreshInstalledState()
                if (OverdriveInstaller.isInstalled(this@SentryActivity)) return@launch
            }
        }
    }

    private fun openSentry() {
        val launch = OverdriveInstaller.launchIntent(this)
        if (launch == null) {
            Toast.makeText(this, R.string.sentry_open_failed, Toast.LENGTH_SHORT).show()
            refreshInstalledState()
            return
        }
        try {
            startActivity(launch)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.sentry_open_failed, Toast.LENGTH_SHORT).show()
            refreshInstalledState()
        }
    }

    private fun downloadAndInstall() {
        if (busy) return
        setBusy(true, getString(R.string.settings_updates_checking))
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        progress.progress = 0

        lifecycleScope.launch {
            try {
                if (!ensureInstallAllowed()) return@launch
                val release = OverdriveInstaller.fetchLatestRelease()
                withContext(Dispatchers.Main) {
                    statusText.text = getString(
                        R.string.sentry_install_found,
                        release.versionName,
                    )
                }
                val dest = OverdriveInstaller.cacheFile(this@SentryActivity)
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
                    try {
                        installLauncher.launch(
                            AppUpdater.installApkIntent(this@SentryActivity, dest),
                        )
                        watchForPackageChange()
                    } catch (e: ActivityNotFoundException) {
                        setBusy(
                            false,
                            getString(
                                R.string.settings_updates_failed,
                                e.message ?: e.javaClass.simpleName,
                            ),
                        )
                    }
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
                        this@SentryActivity,
                        R.string.settings_updates_signature_hint,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private suspend fun ensureInstallAllowed(): Boolean {
        if (AppUpdater.canInstallPackages(this)) return true

        val pkg = packageName
        AdbPermissionManager.runShellBatch(
            this,
            listOf(
                "appops set $pkg REQUEST_INSTALL_PACKAGES allow",
                "cmd appops set $pkg REQUEST_INSTALL_PACKAGES allow",
            ),
        )
        if (AppUpdater.canInstallPackages(this)) return true

        val opened = withContext(Dispatchers.Main) {
            AppUpdater.openInstallPermissionSettings(this@SentryActivity)
        }
        if (opened) {
            withContext(Dispatchers.Main) {
                setBusy(false, getString(R.string.settings_updates_need_permission))
            }
            return false
        }

        withContext(Dispatchers.Main) {
            statusText.text = getString(R.string.settings_updates_install_anyway)
        }
        return true
    }

    private fun setBusy(value: Boolean, status: String) {
        busy = value
        installButton.isEnabled = !value
        openButton.isEnabled = !value
        statusText.text = status
        if (!value) progress.visibility = View.GONE
    }
}
