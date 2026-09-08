package com.sphy.airconcontroller

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sphy.airconcontroller.adb.AdbPermissionManager
import kotlinx.coroutines.launch

/** Hub: ADB authorize + entry points to Climate and DiKey probe menus. */
class MainActivity : AppCompatActivity() {
    private lateinit var setupStatusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupStatusText = findViewById(R.id.setupStatusText)

        findViewById<Button>(R.id.authorizeButton).setOnClickListener {
            if (!AdbPermissionManager.isPortOpen()) {
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            }
            lifecycleScope.launch { AdbPermissionManager.runSetup(this@MainActivity) }
        }

        findViewById<Button>(R.id.openClimateButton).setOnClickListener {
            startActivity(Intent(this, ClimateTestActivity::class.java))
        }
        findViewById<Button>(R.id.openDikeyButton).setOnClickListener {
            startActivity(Intent(this, DiKeyProbeActivity::class.java))
        }
        findViewById<Button>(R.id.openUsbButton).setOnClickListener {
            startActivity(Intent(this, UsbProbeActivity::class.java))
        }

        observeAdbState()
        maybePromptHiddenApiConsent()
        startAdbSetupIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        refreshSetupStatus()
    }

    private fun observeAdbState() {
        lifecycleScope.launch {
            AdbPermissionManager.state.collect { state ->
                setupStatusText.text = when (state) {
                    is AdbPermissionManager.SetupState.Idle ->
                        if (AdbPermissionManager.isSetupComplete(this@MainActivity)) {
                            getString(R.string.setup_ready)
                        } else {
                            getString(R.string.setup_needed)
                        }
                    is AdbPermissionManager.SetupState.Connecting -> getString(R.string.setup_connecting)
                    is AdbPermissionManager.SetupState.WaitingAuth -> getString(R.string.setup_waiting_auth)
                    is AdbPermissionManager.SetupState.Granting -> getString(R.string.setup_granting)
                    is AdbPermissionManager.SetupState.Done -> getString(R.string.setup_ready)
                    is AdbPermissionManager.SetupState.Failed -> getString(R.string.setup_failed, state.reason)
                }
            }
        }
    }

    private fun startAdbSetupIfNeeded() {
        lifecycleScope.launch {
            AdbPermissionManager.ensureVehicleApiAccess(this@MainActivity)
            if (!AdbPermissionManager.isSetupComplete(this@MainActivity)) {
                AdbPermissionManager.runSetup(this@MainActivity)
            }
            refreshSetupStatus()
        }
    }

    private fun refreshSetupStatus() {
        if (AdbPermissionManager.state.value is AdbPermissionManager.SetupState.Idle) {
            setupStatusText.text = if (AdbPermissionManager.isSetupComplete(this)) {
                getString(R.string.setup_ready)
            } else {
                getString(R.string.setup_needed)
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
}
