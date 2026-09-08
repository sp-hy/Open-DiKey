package com.sphy.airconcontroller

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.ble.BleConnectionRegistry
import com.sphy.airconcontroller.ble.BleConnectionState
import com.sphy.airconcontroller.ble.toDisplayString
import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.service.AirconForegroundService
import com.sphy.airconcontroller.storage.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Vehicle climate API test controls + optional ESP32 listener. */
class ClimateTestActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings
    private lateinit var ac: BydAcController
    private lateinit var acStatusText: TextView
    private lateinit var lastResultText: TextView
    private lateinit var bleStatusText: TextView
    private lateinit var dumpText: TextView
    private lateinit var deviceNameInput: TextInputEditText

    private val bleStatusListener: (BleConnectionState) -> Unit = { state ->
        bleStatusText.text = state.toDisplayString(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_climate_test)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.climate_title)

        settings = AppSettings(this)
        ac = BydAcController(this)

        acStatusText = findViewById(R.id.acStatusText)
        lastResultText = findViewById(R.id.lastResultText)
        bleStatusText = findViewById(R.id.bleStatusText)
        dumpText = findViewById(R.id.dumpText)
        deviceNameInput = findViewById(R.id.deviceNameInput)
        deviceNameInput.setText(settings.espDeviceName)

        findViewById<Button>(R.id.refreshButton).setOnClickListener { refreshStatus() }
        findViewById<Button>(R.id.acOnButton).setOnClickListener { runAc("Climate ON") { ac.start() } }
        findViewById<Button>(R.id.acOffButton).setOnClickListener { runAc("Climate OFF") { ac.stop() } }
        findViewById<Button>(R.id.driverTempDownButton).setOnClickListener { runAc("Driver temp −") { ac.nudgeDriverTemp(-1) } }
        findViewById<Button>(R.id.driverTempUpButton).setOnClickListener { runAc("Driver temp +") { ac.nudgeDriverTemp(1) } }
        findViewById<Button>(R.id.passengerTempDownButton).setOnClickListener { runAc("Passenger temp −") { ac.nudgePassengerTemp(-1) } }
        findViewById<Button>(R.id.passengerTempUpButton).setOnClickListener { runAc("Passenger temp +") { ac.nudgePassengerTemp(1) } }
        findViewById<Button>(R.id.fanDownButton).setOnClickListener { runAc("Fan speed −") { ac.nudgeFan(-1) } }
        findViewById<Button>(R.id.fanUpButton).setOnClickListener { runAc("Fan speed +") { ac.nudgeFan(1) } }
        findViewById<Button>(R.id.autoButton).setOnClickListener { runAc("Auto mode") { ac.toggleAuto() } }
        findViewById<Button>(R.id.recircButton).setOnClickListener { runAc("Recirculate / fresh air") { ac.toggleRecirc() } }
        findViewById<Button>(R.id.frontDemistButton).setOnClickListener { runAc("Front demist") { ac.toggleFrontDefrost() } }
        findViewById<Button>(R.id.rearDemistButton).setOnClickListener { runAc("Rear window and mirrors") { ac.toggleRearWindowHeat() } }
        findViewById<Button>(R.id.airOnlyButton).setOnClickListener { runAc("Air only") { ac.toggleAirOnly() } }
        findViewById<Button>(R.id.maxCoolButton).setOnClickListener { runAc("Max cooling") { ac.setMaxCool(true) } }
        findViewById<Button>(R.id.dumpMethodsButton).setOnClickListener {
            lifecycleScope.launch {
                val dump = withContext(Dispatchers.IO) { ac.dumpMethods() }
                dumpText.text = dump
                val path = withContext(Dispatchers.IO) { persistDump(dump) }
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("AC dump", dump))
                Snackbar.make(dumpText, getString(R.string.dump_saved, path), Snackbar.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.startServiceButton).setOnClickListener {
            settings.espDeviceName = deviceNameInput.text?.toString()?.trim().orEmpty()
                .ifBlank { AppSettings.DEFAULT_DEVICE_NAME }
            if (!hasBluetoothPermissions()) {
                requestBluetoothPermissions()
                Snackbar.make(it, R.string.missing_permissions, Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(this, Intent(this, AirconForegroundService::class.java))
            Snackbar.make(it, R.string.service_running, Snackbar.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.stopServiceButton).setOnClickListener {
            stopService(Intent(this, AirconForegroundService::class.java))
            Snackbar.make(it, R.string.service_stopped, Snackbar.LENGTH_SHORT).show()
        }

        refreshStatus()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStart() {
        super.onStart()
        BleConnectionRegistry.addListener(bleStatusListener)
        refreshStatus()
    }

    override fun onStop() {
        BleConnectionRegistry.removeListener(bleStatusListener)
        super.onStop()
    }

    private fun runAc(label: String, action: () -> BydAcController.CommandResult) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            lastResultText.text = getString(
                R.string.last_result_fmt,
                label,
                result.method,
                result.detail
            )
            val snack = if (result.success) {
                getString(R.string.command_ok, label)
            } else {
                getString(R.string.command_fail, label, result.detail)
            }
            Snackbar.make(lastResultText, snack, Snackbar.LENGTH_LONG).show()
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { ac.snapshot() }
            acStatusText.text = snap.toDisplayString()
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        val perms = requiredPermissions()
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestBluetoothPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), 1001)
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private suspend fun persistDump(dump: String): String {
        val file = File(getExternalFilesDir(null) ?: filesDir, "ac-dump.txt")
        file.writeText(dump)
        runCatching {
            AdbPermissionManager.runShellCommand(
                this,
                "cp ${file.absolutePath} /data/local/tmp/ac-dump.txt"
            )
        }
        Log.w(DUMP_TAG, "dump begin ${dump.lineSequence().count()} lines -> ${file.absolutePath}")
        dump.lineSequence().forEach { line ->
            if (line.isNotEmpty()) Log.w(DUMP_TAG, line.take(4000))
        }
        Log.w(DUMP_TAG, "dump end — pull /data/local/tmp/ac-dump.txt")
        return file.absolutePath
    }

    companion object {
        private const val DUMP_TAG = "BydAcDump"
    }
}
