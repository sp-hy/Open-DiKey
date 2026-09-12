package com.sphy.airconcontroller

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.byd.BydSeatController
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Vehicle climate API test controls. */
class ClimateTestActivity : OpenDiKeyActivity() {
    private lateinit var ac: BydAcController
    private lateinit var seats: BydSeatController
    private lateinit var acStatusText: TextView
    private lateinit var lastResultText: TextView
    private lateinit var dumpText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_climate_test)

        ac = BydAcController(this)
        seats = BydSeatController(this)
        acStatusText = findViewById(R.id.acStatusText)
        lastResultText = findViewById(R.id.lastResultText)
        dumpText = findViewById(R.id.dumpText)

        findViewById<android.widget.ImageButton>(R.id.climateBackButton).setOnClickListener {
            finish()
        }

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
        findViewById<Button>(R.id.syncButton).setOnClickListener { runAc("Temp sync") { ac.toggleSync() } }
        findViewById<Button>(R.id.recircButton).setOnClickListener { runAc("Recirculate / fresh air") { ac.toggleRecirc() } }
        findViewById<Button>(R.id.frontDemistButton).setOnClickListener { runAc("Front demist") { ac.toggleFrontDefrost() } }
        findViewById<Button>(R.id.rearDemistButton).setOnClickListener { runAc("Rear window and mirrors") { ac.toggleRearWindowHeat() } }
        findViewById<Button>(R.id.airOnlyButton).setOnClickListener { runAc("Air only") { ac.toggleAirOnly() } }
        findViewById<Button>(R.id.maxCoolButton).setOnClickListener { runAc("Max cooling") { ac.setMaxCool(true) } }
        findViewById<Button>(R.id.seatHeatDriverButton).setOnClickListener {
            Log.i(SEAT_TAG, "Driver seat heating clicked")
            runSeat(getString(R.string.seat_heat_driver)) {
                seats.cycleHeating(BydSeatController.Zone.DRIVER)
            }
        }
        findViewById<Button>(R.id.seatVentDriverButton).setOnClickListener {
            Log.i(SEAT_TAG, "Driver seat cooling clicked")
            runSeat(getString(R.string.seat_vent_driver)) {
                seats.cycleVentilation(BydSeatController.Zone.DRIVER)
            }
        }
        findViewById<Button>(R.id.seatHeatPassengerButton).setOnClickListener {
            Log.i(SEAT_TAG, "Passenger seat heating clicked")
            runSeat(getString(R.string.seat_heat_passenger)) {
                seats.cycleHeating(BydSeatController.Zone.PASSENGER)
            }
        }
        findViewById<Button>(R.id.seatVentPassengerButton).setOnClickListener {
            Log.i(SEAT_TAG, "Passenger seat cooling clicked")
            runSeat(getString(R.string.seat_vent_passenger)) {
                seats.cycleVentilation(BydSeatController.Zone.PASSENGER)
            }
        }
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
    }

    override fun onStart() {
        super.onStart()
        refreshStatus()
    }

    private fun runAc(label: String, action: () -> BydAcController.CommandResult) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            showResult(label, result.method, result.detail, result.success)
        }
    }

    private fun runSeat(label: String, action: () -> BydSeatController.CommandResult) {
        lastResultText.text = getString(R.string.last_result_fmt, label, "…", "running")
        lifecycleScope.launch {
            Log.i(SEAT_TAG, "$label starting")
            val result = try {
                withContext(Dispatchers.IO) { action() }
            } catch (t: Throwable) {
                Log.e(SEAT_TAG, "$label crashed", t)
                BydSeatController.CommandResult(
                    false,
                    "seat",
                    null,
                    "${t.javaClass.simpleName}: ${t.message}"
                )
            }
            Log.i(SEAT_TAG, "$label → success=${result.success} ${result.method} ${result.detail}")
            showResult(label, result.method, result.detail, result.success)
        }
    }

    private fun showResult(label: String, method: String, detail: String, success: Boolean) {
        lastResultText.text = getString(R.string.last_result_fmt, label, method, detail)
        val snack = if (success) {
            getString(R.string.command_ok, label)
        } else {
            getString(R.string.command_fail, label, detail)
        }
        Snackbar.make(lastResultText, snack, Snackbar.LENGTH_LONG).show()
        refreshStatus()
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { ac.snapshot() }
            val seatLine = try {
                withContext(Dispatchers.IO) { seats.statusLine() }
            } catch (t: Throwable) {
                Log.w(SEAT_TAG, "statusLine", t)
                "Seat: error (${t.message})"
            }
            // Set once — concurrent refreshStatus() calls used to append seat lines twice.
            acStatusText.text = buildString {
                append(snap.toDisplayString())
                if (seatLine.isNotBlank()) {
                    append('\n')
                    append(seatLine)
                }
            }
        }
    }

    private suspend fun persistDump(dump: String): String {
        val file = File(getExternalFilesDir(null) ?: filesDir, "ac-dump.txt")
        file.writeText(dump)
        // Shark DiLink: /data/local/tmp is not writable — mirror to sdcard for adb pull.
        runCatching {
            AdbPermissionManager.runShellCommand(
                this,
                "cp ${file.absolutePath} /sdcard/ac-dump.txt"
            )
        }
        Log.w(DUMP_TAG, "dump begin ${dump.lineSequence().count()} lines -> ${file.absolutePath}")
        dump.lineSequence().forEach { line ->
            if (line.isNotEmpty()) Log.w(DUMP_TAG, line.take(4000))
        }
        Log.w(DUMP_TAG, "dump end — pull /sdcard/ac-dump.txt or ${file.absolutePath}")
        return file.absolutePath
    }

    companion object {
        private const val DUMP_TAG = "BydAcDump"
        private const val SEAT_TAG = "BydSeatController"
    }
}
