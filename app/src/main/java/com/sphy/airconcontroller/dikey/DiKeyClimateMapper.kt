package com.sphy.airconcontroller.dikey

import android.os.Handler
import android.os.Looper
import com.sphy.airconcontroller.byd.BydAcController
import java.util.concurrent.Executors

/**
 * Pushing a DiKey button downwards (click) → vehicle climate (fixed).
 * Dial click toggles temp / fan on that side (fixed). Dial long-press and
 * other remappable presses are handled by [DiKeyUpMapper]. Rotate writes the value.
 */
class DiKeyClimateMapper(
    private val ac: BydAcController,
    private val dikey: DiKeyController,
    private val onLog: (String) -> Unit
) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var leftMode = DialMode.TEMP
    private var rightMode = DialMode.TEMP

    fun handle(event: DiKeyEvent) {
        when (event) {
            is DiKeyEvent.Button -> handleButton(event)
            is DiKeyEvent.Encoder -> handleEncoder(event)
            is DiKeyEvent.Raw -> Unit
        }
    }

    private fun handleButton(event: DiKeyEvent.Button) {
        if (event.direction != "DOWN") return
        if (event.event != "CLICK") return
        val action = buttonAction(event.logicalId) ?: return
        io.execute {
            ac.bind()
            val result = action.invoke()
            main.post {
                onLog("Climate · btn${event.logicalId} DOWN · ${result.method} → ${result.detail}")
            }
        }
    }

    private fun buttonAction(logicalId: Int): (() -> BydAcController.CommandResult)? =
        when (logicalId) {
            1 -> { { ac.togglePower() } }
            2 -> { { ac.toggleCompressor() } }
            3 -> { { ac.cycleWindDirection() } }
            4 -> { { ac.toggleRecirc() } }
            5 -> { { ac.toggleAuto() } }
            6 -> { { ac.toggleFrontDefrost() } }
            7 -> { { ac.toggleRearWindowHeat() } }
            8 -> { { ac.toggleAirOnly() } }
            9 -> { { ac.toggleSync() } }
            10 -> { { ac.toggleMaxCool() } }
            else -> null
        }

    private fun handleEncoder(event: DiKeyEvent.Encoder) {
        val left = event.side == "LEFT"
        when (event.event) {
            "SINGLE_CLICK" -> cycleDialMode(left)
            "ROTATE_RIGHT", "ROTATE_LEFT" -> {
                val inferred = DialMode.fromDisplayType(event.displayType) ?: return
                if (left) leftMode = inferred else rightMode = inferred
                applyDialValue(left, event.value)
            }
            else -> DialMode.fromDisplayType(event.displayType)?.let { inferred ->
                if (left) leftMode = inferred else rightMode = inferred
            }
        }
    }

    private fun cycleDialMode(left: Boolean) {
        val current = if (left) leftMode else rightMode
        val next = current.next()
        if (left) leftMode = next else rightMode = next
        io.execute {
            ac.bind()
            val snap = ac.snapshot()
            val type = displayType(left, next)
            val value = when (next) {
                DialMode.TEMP -> {
                    val raw = if (left) snap.passengerTempC else snap.driverTempC
                    type.clamp(raw ?: type.defaultValue)
                }
                DialMode.FAN -> type.clamp(snap.fanLevel ?: type.defaultValue)
            }
            main.post {
                dikey.sendDialDisplay(left, type.code, value)
                onLog(
                    "Climate · ${if (left) "LEFT" else "RIGHT"} dial → ${type.label} = $value"
                )
            }
        }
    }

    private fun applyDialValue(left: Boolean, value: Int) {
        when (if (left) leftMode else rightMode) {
            DialMode.TEMP -> io.execute {
                ac.bind()
                val temp = value.coerceIn(BydAcController.TEMP_MIN, BydAcController.TEMP_MAX)
                val result = if (left) ac.setPassengerTemp(temp) else ac.setDriverTemp(temp)
                main.post {
                    onLog(
                        "Climate · ${if (left) "passenger" else "driver"} temp $temp → ${result.detail}"
                    )
                }
            }
            DialMode.FAN -> io.execute {
                ac.bind()
                val result = ac.setFanLevel(value)
                main.post {
                    onLog("Climate · fan $value → ${result.detail}")
                }
            }
        }
    }

    private fun displayType(left: Boolean, mode: DialMode): DialDisplayType =
        when (mode) {
            DialMode.TEMP -> if (left) DialDisplayType.PASSENGER_TEMP else DialDisplayType.DRIVER_TEMP
            DialMode.FAN -> if (left) DialDisplayType.PASSENGER_FAN else DialDisplayType.DRIVER_FAN
        }

    private enum class DialMode {
        TEMP, FAN;

        fun next(): DialMode = when (this) {
            TEMP -> FAN
            FAN -> TEMP
        }

        companion object {
            fun fromDisplayType(code: Int): DialMode? =
                when (DialDisplayType.fromCode(code)) {
                    DialDisplayType.DRIVER_TEMP, DialDisplayType.PASSENGER_TEMP -> TEMP
                    DialDisplayType.DRIVER_FAN, DialDisplayType.PASSENGER_FAN -> FAN
                    else -> null
                }
        }
    }
}
