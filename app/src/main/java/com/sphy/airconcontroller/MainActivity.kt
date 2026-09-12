package com.sphy.airconcontroller

import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.dikey.DiKeySession
import com.sphy.airconcontroller.usb.UsbPermissionReceiver
import kotlinx.coroutines.launch

class MainActivity : OpenDiKeyActivity() {
    private lateinit var session: DiKeySession
    private lateinit var connStatus: android.widget.TextView

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
