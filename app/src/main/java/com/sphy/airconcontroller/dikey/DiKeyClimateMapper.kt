package com.sphy.airconcontroller.dikey

import android.os.Handler
import android.os.Looper
import com.sphy.airconcontroller.byd.BydAcController
import java.util.concurrent.Executors

/**
 * DOWN-face DiKey clicks → vehicle climate. Dial click cycles temp / fan / media
 * on that side (left = passenger, right = driver). Rotate writes the value.
 * UP-face buttons and encoder UP are ignored.
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
    private var leftMedia = DialDisplayType.MEDIA_VOLUME.defaultValue
    private var rightMedia = DialDisplayType.MEDIA_VOLUME.defaultValue

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
            10 -> { { ac.toggleMaxCool() } }
            else -> null
        }

    private fun handleEncoder(event: DiKeyEvent.Encoder) {
        val left = event.side == "LEFT"
        DialMode.fromDisplayType(event.displayType)?.let { inferred ->
            if (left) leftMode = inferred else rightMode = inferred
        }
        when (event.event) {
            "SINGLE_CLICK" -> cycleDialMode(left, event.displayType)
            "ROTATE_RIGHT", "ROTATE_LEFT" -> {
                if (DialMode.fromDisplayType(event.displayType) != null) {
                    applyDialValue(left, event.value)
                }
            }
            else -> Unit
        }
    }

    private fun cycleDialMode(left: Boolean, currentType: Int) {
        val current = DialMode.fromDisplayType(currentType) ?: if (left) leftMode else rightMode
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
                DialMode.MEDIA -> {
                    val stored = if (left) leftMedia else rightMedia
                    type.clamp(stored)
                }
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
            DialMode.MEDIA -> {
                val clamped = DialDisplayType.MEDIA_VOLUME.clamp(value)
                if (left) leftMedia = clamped else rightMedia = clamped
                onLog("Climate · ${if (left) "LEFT" else "RIGHT"} media = $clamped (LCD only)")
            }
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
            DialMode.MEDIA -> DialDisplayType.MEDIA_VOLUME
        }

    private enum class DialMode {
        TEMP, FAN, MEDIA;

        fun next(): DialMode = when (this) {
            TEMP -> FAN
            FAN -> MEDIA
            MEDIA -> TEMP
        }

        companion object {
            fun fromDisplayType(code: Int): DialMode? =
                when (DialDisplayType.fromCode(code)) {
                    DialDisplayType.DRIVER_TEMP, DialDisplayType.PASSENGER_TEMP -> TEMP
                    DialDisplayType.DRIVER_FAN, DialDisplayType.PASSENGER_FAN -> FAN
                    DialDisplayType.MEDIA_VOLUME -> MEDIA
                    else -> null
                }
        }
    }
}
