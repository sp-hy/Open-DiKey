package com.sphy.airconcontroller

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Debug dump of live vehicle reads + reflective method dump. */
class VehicleDumpActivity : OpenDiKeyActivity() {
    private lateinit var info: BydVehicleInfoController
    private lateinit var statusText: TextView
    private lateinit var dumpText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle_dump)

        info = BydVehicleInfoController(this)
        statusText = findViewById(R.id.vehicleDumpStatusText)
        dumpText = findViewById(R.id.vehicleDumpText)

        findViewById<android.widget.ImageButton>(R.id.vehicleDumpBackButton).setOnClickListener {
            finish()
        }
        findViewById<Button>(R.id.vehicleDumpRefreshButton).setOnClickListener { refreshStatus() }
        findViewById<Button>(R.id.vehicleDumpAllButton).setOnClickListener {
            lifecycleScope.launch {
                val dump = withContext(Dispatchers.IO) { info.dumpAll() }
                dumpText.text = dump
                val path = withContext(Dispatchers.IO) { persistDump(dump) }
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Vehicle dump", dump))
                Snackbar.make(
                    dumpText,
                    getString(R.string.dump_saved, path),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

        if (intent.getBooleanExtra(EXTRA_AUTO_DUMP, false)) {
            lifecycleScope.launch {
                val dump = withContext(Dispatchers.IO) { info.dumpAll() }
                dumpText.text = dump
                withContext(Dispatchers.IO) { persistDump(dump) }
                finish()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val snap = withContext(Dispatchers.IO) { info.snapshot() }
                    statusText.text = snap.toDisplayString()
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { info.snapshot() }
            statusText.text = snap.toDisplayString()
        }
    }

    private suspend fun persistDump(dump: String): String {
        val file = File(getExternalFilesDir(null) ?: filesDir, "vehicle-dump.txt")
        file.writeText(dump)
        runCatching {
            AdbPermissionManager.runShellCommand(
                this,
                "cp ${file.absolutePath} /data/local/tmp/vehicle-dump.txt"
            )
        }
        Log.w(DUMP_TAG, "dump begin ${dump.lineSequence().count()} lines -> ${file.absolutePath}")
        dump.lineSequence().forEach { line ->
            if (line.isNotEmpty()) Log.w(DUMP_TAG, line.take(4000))
        }
        Log.w(DUMP_TAG, "dump end — pull /data/local/tmp/vehicle-dump.txt")
        return file.absolutePath
    }

    companion object {
        const val EXTRA_AUTO_DUMP = "autoDump"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val DUMP_TAG = "BydVehicleDump"
    }
}
