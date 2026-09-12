package com.sphy.airconcontroller

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.switchmaterial.SwitchMaterial
import com.sphy.airconcontroller.byd.BydTyreController
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import com.sphy.airconcontroller.byd.TabletSocReader
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.ui.AttitudeHorizonView
import com.sphy.airconcontroller.ui.GradientPercentBar
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.sqrt

/** Live vehicle dashboard — battery, dynamics, tyres in three orange-bordered columns. */
class VehicleInfoActivity : OpenDiKeyActivity() {
    private lateinit var info: BydVehicleInfoController
    private lateinit var tabletSoc: TabletSocReader
    private lateinit var settings: AppSettings
    private lateinit var colBattery: LinearLayout
    private lateinit var colDynamics: LinearLayout
    private lateinit var colTyres: LinearLayout
    private var dashboardReady = false
    private var sensorManager: SensorManager? = null
    private var lastPitchDeg: Double? = null
    private var lastRollDeg: Double? = null

    private lateinit var socBar: PercentRow
    private lateinit var sohBar: PercentRow
    private lateinit var fuelBar: PercentRow
    private lateinit var batterySubtitle: TextView
    private lateinit var batteryMetaHost: LinearLayout

    private lateinit var accelBar: PercentRow
    private lateinit var brakeBar: PercentRow
    private lateinit var driveHero: TextView
    private lateinit var driveSubtitle: TextView
    private lateinit var driveMetaHost: LinearLayout
    private lateinit var engineHost: LinearLayout
    private lateinit var chassisHost: LinearLayout
    private lateinit var mileageHost: LinearLayout
    private lateinit var consumptionHost: LinearLayout

    private lateinit var tyreSlots: List<TyreSlot>
    private lateinit var tyreUnboundHint: TextView
    private lateinit var attitudeView: AttitudeHorizonView
    private lateinit var attitudePitchView: AttitudeHorizonView
    private lateinit var attitudeRollView: AttitudeHorizonView
    private lateinit var attitudeSplitRow: View
    private lateinit var attitudeSplitSwitch: SwitchMaterial
    private lateinit var attitudePitch: TextView
    private lateinit var attitudeRoll: TextView
    private lateinit var tabletMetaHost: LinearLayout

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!dashboardReady || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val ax = event.values[0].toDouble()
            val ay = event.values[1].toDouble()
            val az = event.values[2].toDouble()
            val pitch = Math.toDegrees(atan2(-ax, sqrt(ay * ay + az * az)))
            val roll = Math.toDegrees(atan2(ay, az))
            lastPitchDeg = pitch
            lastRollDeg = roll
            bindAttitude(pitch, roll)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vehicle_info)

        info = BydVehicleInfoController(this)
        tabletSoc = TabletSocReader(this)
        settings = AppSettings(this)
        colBattery = findViewById(R.id.vehicleColBattery)
        colDynamics = findViewById(R.id.vehicleColDynamics)
        colTyres = findViewById(R.id.vehicleColTyres)

        findViewById<android.widget.ImageButton>(R.id.vehicleInfoBackButton).setOnClickListener {
            finish()
        }

        buildDashboard()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val snap = withContext(Dispatchers.IO) { info.snapshot(includeImu = false) }
                    val soc = withContext(Dispatchers.IO) { tabletSoc.snapshot() }
                    bindSnapshot(snap)
                    bindTabletSoc(soc)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val drive = withContext(Dispatchers.IO) { info.readDriveLive() }
                    if (dashboardReady) {
                        driveHero.visibility = View.VISIBLE
                        driveHero.text = fmt(drive.speedKmh, " km/h")
                        accelBar.set(drive.accelPedalPct)
                        brakeBar.set(drive.brakePedalPct)
                    }
                    delay(DRIVE_POLL_INTERVAL_MS)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sm = getSystemService(SensorManager::class.java)
        sensorManager = sm
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm != null && accel != null) {
            sm.registerListener(accelListener, accel, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onStop() {
        sensorManager?.unregisterListener(accelListener)
        sensorManager = null
        super.onStop()
    }

    private fun buildDashboard() {
        if (dashboardReady) return
        val inflater = LayoutInflater.from(this)

        // —— Battery column ——
        val batteryBlock = inflater.inflate(R.layout.view_info_block, colBattery, false)
        batteryBlock.findViewById<TextView>(R.id.infoBlockTitle)
            .text = getString(R.string.vehicle_info_block_battery)
        batteryBlock.findViewById<TextView>(R.id.infoBlockHero).visibility = View.GONE
        batterySubtitle = batteryBlock.findViewById(R.id.infoBlockSubtitle)
        batteryMetaHost = batteryBlock.findViewById(R.id.infoBlockRows)
        socBar = addPercentRow(batteryMetaHost, getString(R.string.vehicle_info_row_soc))
        sohBar = addPercentRow(batteryMetaHost, getString(R.string.vehicle_info_row_soh))
        fuelBar = addPercentRow(batteryMetaHost, getString(R.string.vehicle_info_row_fuel))
        colBattery.addView(batteryBlock)

        consumptionHost = addSimpleBlock(colBattery, getString(R.string.vehicle_info_block_consumption))
        tabletMetaHost = addSimpleBlock(colBattery, getString(R.string.vehicle_info_block_tablet))
        stretchColumnBlocks(colBattery)

        // —— Dynamics column ——
        val driveBlock = inflater.inflate(R.layout.view_info_block, colDynamics, false)
        driveBlock.findViewById<TextView>(R.id.infoBlockTitle)
            .text = getString(R.string.vehicle_info_block_drive)
        driveHero = driveBlock.findViewById(R.id.infoBlockHero)
        driveSubtitle = driveBlock.findViewById(R.id.infoBlockSubtitle)
        driveMetaHost = driveBlock.findViewById(R.id.infoBlockRows)
        accelBar = addPercentRow(driveMetaHost, getString(R.string.vehicle_info_row_accel))
        brakeBar = addPercentRow(driveMetaHost, getString(R.string.vehicle_info_row_brake))
        colDynamics.addView(driveBlock)

        mileageHost = addSimpleBlock(colDynamics, getString(R.string.vehicle_info_block_mileage))
        engineHost = addSimpleBlock(colDynamics, getString(R.string.vehicle_info_block_engine))
        stretchColumnBlocks(colDynamics)

        // —— Tyres / attitude column ——
        val tyresBlock = inflater.inflate(R.layout.view_tyres_block, colTyres, false)
        val slotsRow = tyresBlock.findViewById<LinearLayout>(R.id.tyreSlotsRow)
        tyreUnboundHint = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@VehicleInfoActivity, R.color.text_muted))
            text = getString(R.string.vehicle_info_tyres_unbound)
            visibility = View.GONE
        }
        slotsRow.addView(
            tyreUnboundHint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        tyreSlots = BydTyreController.Corner.entries.map { corner ->
            val slot = inflater.inflate(R.layout.view_tyre_slot, slotsRow, false)
            slot.findViewById<TextView>(R.id.tyreCornerLabel).text = corner.label
            slotsRow.addView(slot)
            TyreSlot(
                root = slot,
                icon = slot.findViewById(R.id.tyreIcon),
                psi = slot.findViewById(R.id.tyrePsiText),
                temp = slot.findViewById(R.id.tyreTempText),
            )
        }
        colTyres.addView(tyresBlock)

        val attitudeBlock = inflater.inflate(R.layout.view_attitude_block, colTyres, false)
        attitudeView = attitudeBlock.findViewById(R.id.attitudeHorizon)
        attitudePitchView = attitudeBlock.findViewById(R.id.attitudePitchHorizon)
        attitudeRollView = attitudeBlock.findViewById(R.id.attitudeRollHorizon)
        attitudeSplitRow = attitudeBlock.findViewById(R.id.attitudeSplitRow)
        attitudeSplitSwitch = attitudeBlock.findViewById(R.id.attitudeSplitSwitch)
        attitudePitch = attitudeBlock.findViewById(R.id.attitudePitchText)
        attitudeRoll = attitudeBlock.findViewById(R.id.attitudeRollText)
        attitudePitchView.axisMode = AttitudeHorizonView.AxisMode.PITCH
        attitudeRollView.axisMode = AttitudeHorizonView.AxisMode.ROLL
        attitudeSplitSwitch.isChecked = settings.vehicleInfoAttitudeSplit
        applyAttitudeSplitMode(settings.vehicleInfoAttitudeSplit)
        attitudeSplitSwitch.setOnCheckedChangeListener { _, checked ->
            settings.vehicleInfoAttitudeSplit = checked
            applyAttitudeSplitMode(checked)
            bindAttitude(lastPitchDeg, lastRollDeg)
        }
        colTyres.addView(attitudeBlock)

        chassisHost = addSimpleBlock(colTyres, getString(R.string.vehicle_info_block_chassis))
        stretchColumnBlocks(colTyres)

        dashboardReady = true
    }

    private fun applyAttitudeSplitMode(split: Boolean) {
        attitudeView.visibility = if (split) View.GONE else View.VISIBLE
        attitudeSplitRow.visibility = if (split) View.VISIBLE else View.GONE
    }

    private fun bindAttitude(pitch: Double?, roll: Double?) {
        if (!dashboardReady) return
        if (settings.vehicleInfoAttitudeSplit) {
            attitudePitchView.setAttitude(pitch, roll)
            attitudeRollView.setAttitude(pitch, roll)
        } else {
            attitudeView.setAttitude(pitch, roll)
        }
        attitudePitch.text = getString(R.string.vehicle_info_pitch_fmt, fmt(pitch, ""))
        attitudeRoll.text = getString(R.string.vehicle_info_roll_fmt, fmt(roll, ""))
    }

    /** Share leftover column height across blocks; drop last bottom margin so they sit flush. */
    private fun stretchColumnBlocks(col: LinearLayout) {
        val last = col.childCount - 1
        for (i in 0..last) {
            val child = col.getChildAt(i)
            val lp = child.layoutParams as LinearLayout.LayoutParams
            lp.width = LinearLayout.LayoutParams.MATCH_PARENT
            lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
            lp.weight = 1f
            if (i == last) lp.bottomMargin = 0
            child.layoutParams = lp
        }
    }

    private fun addSimpleBlock(parent: LinearLayout, title: String): LinearLayout {
        val block = LayoutInflater.from(this).inflate(R.layout.view_info_block, parent, false)
        block.findViewById<TextView>(R.id.infoBlockTitle).text = title
        block.findViewById<TextView>(R.id.infoBlockHero).visibility = View.GONE
        block.findViewById<TextView>(R.id.infoBlockSubtitle).visibility = View.GONE
        parent.addView(block)
        return block.findViewById(R.id.infoBlockRows)
    }

    private fun addPercentRow(host: LinearLayout, label: String): PercentRow {
        val row = LayoutInflater.from(this).inflate(R.layout.view_percent_row, host, false)
        row.findViewById<TextView>(R.id.percentRowLabel).text = label
        host.addView(row)
        return PercentRow(
            value = row.findViewById(R.id.percentRowValue),
            bar = row.findViewById(R.id.percentRowBar),
        )
    }

    private fun bindSnapshot(snap: BydVehicleInfoController.Snapshot) {
        val b = snap.battery
        socBar.set(b.socPct)
        sohBar.set(b.sohPct)
        fuelBar.set(b.fuelPct)
        batterySubtitle.text = listOfNotNull(
            b.remainingUsableKwh?.let {
                getString(R.string.vehicle_info_usable_fmt, trimNum(it))
            },
            b.packEnergyKwh?.let { getString(R.string.vehicle_info_pack_fmt, trimNum(it)) },
            b.capacityAh?.let { getString(R.string.vehicle_info_capacity_ah_fmt, trimNum(it)) },
        ).joinToString(" · ")
        batterySubtitle.visibility =
            if (batterySubtitle.text.isNullOrBlank()) View.GONE else View.VISIBLE

        // Keep percent rows; refresh trailing text rows after them.
        while (batteryMetaHost.childCount > 3) {
            batteryMetaHost.removeViewAt(3)
        }
        appendTextRows(
            batteryMetaHost,
            listOf(
                getString(R.string.vehicle_info_row_ev_range) to fmt(b.evRangeKm, " km"),
                getString(R.string.vehicle_info_row_fuel_range) to fmt(b.fuelRangeKm, " km"),
                getString(R.string.vehicle_info_row_combined) to fmt(b.combinedRangeKm, " km"),
            ),
        )

        replaceTextRows(
            mileageHost,
            listOf(
                getString(R.string.vehicle_info_row_odo) to fmt(b.odoKm, " km"),
                getString(R.string.vehicle_info_row_ev_miles) to fmt(b.evMilesKm, " km"),
                getString(R.string.vehicle_info_row_hev_miles) to fmt(b.hevMilesKm, " km"),
                getString(R.string.vehicle_info_row_drive_time) to fmt(b.driveTimeH, " h"),
                getString(R.string.vehicle_info_row_avg_speed) to fmt(b.avgSpeedKmh, " km/h"),
            ),
        )
        replaceTextRows(
            consumptionHost,
            listOf(
                getString(R.string.vehicle_info_row_life_ev) to
                    "${fmt(b.lifetimeElecKwh, " kWh")} · ${fmt(b.lifetimeElecAvg, " kWh/100km")}",
                getString(R.string.vehicle_info_row_life_charged) to fmt(b.lifetimeChargedKwh, " kWh"),
                getString(R.string.vehicle_info_row_life_fuel) to
                    "${fmt(b.lifetimeFuelL, " L")} · ${fmt(b.lifetimeFuelAvg, " L/100km")}",
            ),
        )

        val d = snap.dynamics
        driveHero.visibility = View.VISIBLE
        driveHero.text = fmt(d.speedKmh, " km/h")
        driveSubtitle.visibility = View.VISIBLE
        driveSubtitle.text = getString(R.string.vehicle_info_gear_fmt, d.gearLabel ?: "—")
        accelBar.set(d.accelPedalPct)
        brakeBar.set(d.brakePedalPct)
        while (driveMetaHost.childCount > 2) {
            driveMetaHost.removeViewAt(2)
        }
        appendTextRows(
            driveMetaHost,
            listOf(
                getString(R.string.vehicle_info_row_park) to onOff(d.parkBrake),
            ),
        )

        replaceTextRows(
            engineHost,
            listOf(
                getString(R.string.vehicle_info_row_power) to fmt(d.enginePowerKw, " kW"),
                getString(R.string.vehicle_info_row_rpm) to fmt(d.engineRpm),
                getString(R.string.vehicle_info_row_oil) to fmt(d.oilLevel),
                getString(R.string.vehicle_info_row_disp) to fmt(d.displacementL, " L"),
            ),
        )
        replaceTextRows(
            chassisHost,
            listOf(
                getString(R.string.vehicle_info_row_outside) to fmt(d.outsideTempC, "°C"),
                getString(R.string.vehicle_info_row_steer) to fmt(d.steeringDeg, "°"),
            ),
        )

        val tyres = snap.tyres
        if (!tyres.bound) {
            tyreUnboundHint.visibility = View.VISIBLE
            tyreUnboundHint.text = tyres.bindError ?: getString(R.string.vehicle_info_tyres_unbound)
            tyreSlots.forEach { it.root.visibility = View.GONE }
        } else {
            tyreUnboundHint.visibility = View.GONE
            val byCorner = tyres.corners.associateBy { it.corner }
            BydTyreController.Corner.entries.forEachIndexed { i, corner ->
                val slot = tyreSlots[i]
                slot.root.visibility = View.VISIBLE
                val reading = byCorner[corner]
                if (reading == null) {
                    slot.psi.text = "—"
                    slot.temp.text = "—"
                    slot.icon.setColorFilter(
                        ContextCompat.getColor(this, R.color.tyre_fault),
                    )
                } else {
                    slot.psi.text = reading.pressurePsiShort()
                    slot.temp.text = reading.temperatureLabel()
                    val color = when (reading.statusTone()) {
                        BydTyreController.StatusTone.OK -> R.color.tyre_ok
                        BydTyreController.StatusTone.UNDER -> R.color.tyre_under
                        BydTyreController.StatusTone.FAULT -> R.color.tyre_fault
                    }
                    slot.icon.setColorFilter(ContextCompat.getColor(this, color))
                }
            }
        }
    }

    private fun bindTabletSoc(soc: TabletSocReader.Snapshot) {
        if (!dashboardReady) return
        replaceTextRows(
            tabletMetaHost,
            listOf(
                getString(R.string.vehicle_info_row_cpu) to
                    (soc.cpuPct?.let { String.format("%.0f%%", it) } ?: "—"),
                getString(R.string.vehicle_info_row_ram) to
                    if (soc.ramUsedMb != null && soc.ramTotalMb != null) {
                        getString(
                            R.string.vehicle_info_ram_fmt,
                            soc.ramUsedMb.toString(),
                            soc.ramTotalMb.toString(),
                        )
                    } else {
                        "—"
                    },
                getString(R.string.vehicle_info_row_storage) to
                    if (soc.storageUsedGb != null && soc.storageTotalGb != null) {
                        getString(
                            R.string.vehicle_info_storage_fmt,
                            trimNum(soc.storageUsedGb),
                            trimNum(soc.storageTotalGb),
                        )
                    } else {
                        "—"
                    },
            ),
        )
    }

    private fun replaceTextRows(host: LinearLayout, rows: List<Pair<String, String>>) {
        host.removeAllViews()
        appendTextRows(host, rows)
    }

    private fun appendTextRows(host: LinearLayout, rows: List<Pair<String, String>>) {
        val inflater = LayoutInflater.from(this)
        val density = resources.displayMetrics.density
        val gap = (12 * density).toInt()
        val dividerPad = (6 * density).toInt()
        var i = 0
        while (i < rows.size) {
            val a = rows[i]
            val b = rows.getOrNull(i + 1)
            if (b != null && !isWideReading(a) && !isWideReading(b)) {
                val pair = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                pair.addView(
                    inflateInfoRow(inflater, pair, a).also {
                        it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                pair.addView(
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            (1 * density).toInt().coerceAtLeast(1),
                            LinearLayout.LayoutParams.MATCH_PARENT,
                        ).apply {
                            marginStart = gap
                            marginEnd = gap
                            topMargin = dividerPad
                            bottomMargin = dividerPad
                        }
                        setBackgroundColor(ContextCompat.getColor(this@VehicleInfoActivity, R.color.divider_soft))
                    },
                )
                pair.addView(
                    inflateInfoRow(inflater, pair, b).also {
                        it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
                host.addView(pair)
                i += 2
            } else {
                host.addView(inflateInfoRow(inflater, host, a))
                i += 1
            }
        }
    }

    /** Long values (e.g. lifetime consumption with avg) stay full-width. */
    private fun isWideReading(row: Pair<String, String>): Boolean =
        row.second.length > 16 || " · " in row.second

    private fun inflateInfoRow(
        inflater: LayoutInflater,
        parent: LinearLayout,
        row: Pair<String, String>,
    ): View {
        val view = inflater.inflate(R.layout.view_info_row, parent, false)
        view.findViewById<TextView>(R.id.infoRowLabel).text = row.first
        view.findViewById<TextView>(R.id.infoRowValue).text = row.second
        return view
    }

    private fun fmt(v: Double?, suffix: String = ""): String =
        if (v == null) "—" else trimNum(v) + suffix

    private fun onOff(v: Int?): String = when (v) {
        null -> "—"
        0 -> "Off"
        else -> "On"
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format("%.1f", v)

    private data class PercentRow(
        val value: TextView,
        val bar: GradientPercentBar,
    ) {
        fun set(pct: Double?) {
            value.text = if (pct == null) "—" else String.format("%.0f%%", pct)
            bar.setPercent(pct)
        }
    }

    private data class TyreSlot(
        val root: View,
        val icon: ImageView,
        val psi: TextView,
        val temp: TextView,
    )

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private const val DRIVE_POLL_INTERVAL_MS = 200L
    }
}
