package com.sphy.airconcontroller

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.dikey.DiKeySession
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import com.sphy.airconcontroller.update.AppUpdater
import com.sphy.airconcontroller.update.OverdriveInstaller
import com.sphy.airconcontroller.usb.UsbPermissionReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : OpenDiKeyActivity() {
    private lateinit var session: DiKeySession
    private lateinit var connStatus: android.widget.TextView
    private var sentryBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        session = OpenDiKeyApp.from(this).dikey
        connStatus = findViewById(R.id.mainConnStatus)

        findViewById<android.view.View>(R.id.homeColorsButton).setOnClickListener {
            startActivity(Intent(this, ColorConfigActivity::class.java))
        }
        findViewById<android.view.View>(R.id.homeButtonsButton).setOnClickListener {
            startActivity(Intent(this, ButtonMappingActivity::class.java))
        }
        findViewById<android.view.View>(R.id.homeVehicleInfoButton).setOnClickListener {
            startActivity(Intent(this, VehicleInfoActivity::class.java))
        }
        findViewById<android.view.View>(R.id.homeAdasButton).setOnClickListener {
            startActivity(Intent(this, AdasActivity::class.java))
        }
        findViewById<android.view.View>(R.id.homeSentryButton).setOnClickListener {
            openSentryOrInstall()
        }
        findViewById<android.widget.ImageButton>(R.id.mainDebugButton).setOnClickListener {
            startActivity(Intent(this, DebugHubActivity::class.java))
        }
        findViewById<android.widget.ImageButton>(R.id.mainSettingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsHubActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.status.collect { connStatus.text = it }
            }
        }

        maybePromptHiddenApiConsent()
        startAdbSetupIfNeeded()
        handleLaunchIntent(intent)
        maybeRequestBluetoothPermissions()
    }

    private fun openSentryOrInstall() {
        if (sentryBusy) return
        if (OverdriveInstaller.isInstalled(this)) {
            val launch = OverdriveInstaller.launchIntent(this)
            if (launch != null) {
                try {
                    startActivity(launch)
                    return
                } catch (_: ActivityNotFoundException) {
                    // fall through to install prompt
                }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sentry_install_dialog_title)
            .setMessage(R.string.sentry_install_dialog_body)
            .setNegativeButton(R.string.settings_update_later, null)
            .setPositiveButton(R.string.settings_update_install) { _, _ ->
                downloadAndInstallOverdrive()
            }
            .show()
    }

    private fun downloadAndInstallOverdrive() {
        if (sentryBusy) return
        sentryBusy = true
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sentry_install_dialog_title)
            .setMessage(R.string.settings_updates_checking)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                if (!ensureInstallAllowed(progressDialog)) {
                    sentryBusy = false
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    progressDialog.setMessage(getString(R.string.settings_updates_checking))
                }
                val release = OverdriveInstaller.fetchLatestRelease()
                withContext(Dispatchers.Main) {
                    progressDialog.setMessage(getString(R.string.settings_updates_downloading, 0))
                }
                val dest = OverdriveInstaller.cacheFile(this@MainActivity)
                AppUpdater.downloadApk(release.apkUrl, dest) { downloaded, total ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (!progressDialog.isShowing) return@launch
                        progressDialog.setMessage(
                            if (total > 0L) {
                                val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                getString(R.string.settings_updates_downloading, pct)
                            } else {
                                getString(
                                    R.string.settings_updates_downloading_bytes,
                                    downloaded / 1024L,
                                )
                            },
                        )
                    }
                }
                withContext(Dispatchers.Main) {
                    progressDialog.setMessage(getString(R.string.settings_updates_installing))
                    try {
                        startActivity(AppUpdater.installApkIntent(this@MainActivity, dest))
                        progressDialog.dismiss()
                    } catch (e: ActivityNotFoundException) {
                        progressDialog.dismiss()
                        Toast.makeText(
                            this@MainActivity,
                            getString(
                                R.string.settings_updates_failed,
                                e.message ?: e.javaClass.simpleName,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    Toast.makeText(
                        this@MainActivity,
                        getString(
                            R.string.settings_updates_failed,
                            e.message ?: e.javaClass.simpleName,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                sentryBusy = false
            }
        }
    }

    private suspend fun ensureInstallAllowed(progressDialog: androidx.appcompat.app.AlertDialog): Boolean {
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
            AppUpdater.openInstallPermissionSettings(this@MainActivity)
        }
        if (opened) {
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    R.string.settings_updates_need_permission,
                    Toast.LENGTH_LONG,
                ).show()
            }
            return false
        }

        withContext(Dispatchers.Main) {
            progressDialog.setMessage(getString(R.string.settings_updates_install_anyway))
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        session.ensureConnected()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BT_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            session.ensureConnected()
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            UsbPermissionReceiver.ACTION -> session.onUsbPermissionResult(intent)
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = deviceExtra(intent) ?: return
                session.tryConnectUsb(device)
            }
        }
    }

    private fun deviceExtra(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun maybeRequestBluetoothPermissions() {
        if (session.isConnected || session.isUsbOpen) return
        if (session.hasBluetoothPermissions()) return
        ActivityCompat.requestPermissions(
            this,
            session.requiredBluetoothPermissions(),
            BT_PERMISSION_REQUEST
        )
    }

    private fun startAdbSetupIfNeeded() {
        lifecycleScope.launch {
            AdbPermissionManager.ensureVehicleApiAccess(this@MainActivity)
            if (!AdbPermissionManager.isSetupComplete(this@MainActivity)) {
                AdbPermissionManager.runSetup(this@MainActivity)
            }
        }
    }

    private fun maybePromptHiddenApiConsent() {
        if (AdbPermissionManager.hasHiddenApiConsent(this) ||
            AdbPermissionManager.hasBeenPromptedForHiddenApi(this)
        ) {
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.hidden_api_title)
            .setMessage(R.string.hidden_api_body)
            .setCancelable(false)
            .setPositiveButton(R.string.hidden_api_allow) { _, _ ->
                AdbPermissionManager.setHiddenApiConsent(this, true)
                AdbPermissionManager.markHiddenApiPrompted(this)
                lifecycleScope.launch {
                    val applied = AdbPermissionManager.ensureVehicleApiAccess(this@MainActivity)
                    if (applied) AdbPermissionManager.restartApp(this@MainActivity)
                }
            }
            .setNegativeButton(R.string.hidden_api_not_now) { _, _ ->
                AdbPermissionManager.markHiddenApiPrompted(this)
            }
            .show()
    }

    companion object {
        private const val BT_PERMISSION_REQUEST = 1001
    }
}
