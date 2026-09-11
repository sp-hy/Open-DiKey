package com.sphy.airconcontroller.byd

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * Seat heat / vent mirroring OEM `com.byd.hvac` SeatHeatVentilationFragment.
 *
 * HVAC uses [BYDAutoSettingDevice]:
 *   Driver: get/setSeatHeatingState(1, …) / get/setSeatVentilatingState(1, …)
 *   Passenger (non-L1P / Shark): getSeatHeatingNotL1PState() /
 *     getSeatVentilatingNotL1PState(), set still via set*(2, …)
 *   Passenger (L1P): same area-2 get/set as driver
 *
 * API scale: 1 = OFF, 2 = LOW (L1), 3 = HIGH (L2), 4 = 3-level mid.
 * SET path swaps 1↔3 for the car adapter.
 *
 * Tap order (2-level): OFF → HIGH → LOW → OFF (1 → 3 → 2 → 1).
 */
class BydSeatController(context: Context) {
    private val appContext = context.applicationContext
    private val permContext = BydPermissionContext(appContext)

    @Volatile private var settingDevice: Any? = null

    @Volatile
    var lastBindError: String? = null
        private set

    enum class Zone(val area: Int, val label: String) {
        DRIVER(1, "Driver"),
        PASSENGER(2, "Passenger"),
    }

    enum class Kind { HEAT, VENT }

    data class CommandResult(
        val success: Boolean,
        val method: String,
        val raw: Int?,
        val detail: String
    )

    fun bind(): Boolean {
        if (settingDevice != null) return true
        lastBindError = null

        val injected = Dilink5SdkInjector.ensure(appContext)
        if (!injected && !Dilink5SdkInjector.isLoadable(appContext)) {
            lastBindError =
                "Could not inject com.byd.data.collect (hidden-API exemption missing, or OEM app not installed)"
            Log.w(TAG, "bind: $lastBindError")
            return false
        }

        settingDevice = loadInstance(SETTING_CLASS)
        val ok = settingDevice != null
        if (!ok && lastBindError == null) lastBindError = "BYDAutoSettingDevice unavailable"
        Log.i(TAG, "bind setting=${settingDevice != null}")
        return ok
    }

    fun isBound(): Boolean = settingDevice != null || bind()

    fun cycleHeating(zone: Zone = Zone.DRIVER): CommandResult = cycle(Kind.HEAT, zone)

    fun cycleVentilation(zone: Zone = Zone.DRIVER): CommandResult = cycle(Kind.VENT, zone)

    fun statusLine(): String {
        return try {
            if (!ensureDevice()) return "Seat: not bound (${lastBindError ?: "—"})"
            buildString {
                for (zone in Zone.entries) {
                    val heat = normalizeOem(readOem(Kind.HEAT, zone))
                    val vent = normalizeOem(readOem(Kind.VENT, zone))
                    if (isNotEmpty()) append('\n')
                    append(zone.label)
                    append(" seat heating ").append(fmtOem(heat))
                    append(" · seat cooling ").append(fmtOem(vent))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "statusLine failed", t)
            "Seat: error (${t.javaClass.simpleName}: ${t.message})"
        }
    }

    /** Display like the BYD app: 1/3 off, 2/3 L1, 3/3 L2. */
    private fun fmtOem(oem: Int): String {
        val shown = when (oem) {
            in 1..3 -> oem
            4 -> 3
            else -> 1
        }
        return "$shown/3"
    }

    private fun cycle(kind: Kind, zone: Zone): CommandResult {
        Log.i(TAG, "cycle ${zone.name} ${kind.name} start")
        if (!ensureDevice()) return notBound().also { Log.w(TAG, "not bound: $lastBindError") }

        val current = normalizeOem(readOem(kind, zone))
        val next = nextOem(current)
        Log.i(TAG, "cycle ${zone.name} ${kind.name} oem $current → $next")

        val result = writeOem(kind, zone, next)
        pause(150)
        val after = normalizeOem(readOem(kind, zone))
        val detail = "oem $current→$next readback=$after"
        Log.i(TAG, "cycle done success=${result.success} ${result.method} $detail")
        return if (result.success) result.copy(detail = detail) else result.copy(
            detail = "${result.detail} | $detail"
        )
    }

    /**
     * Exact OEM onClick mapping from SeatHeatVentilationFragment (2-level path):
     * HIGH(3)→LOW(2), LOW(2)→OFF(1), MID(4)→HIGH(3), else→HIGH(3).
     */
    private fun nextOem(current: Int): Int = when (current) {
        OEM_HIGH -> OEM_LOW
        OEM_LOW -> OEM_OFF
        4 -> OEM_HIGH
        else -> OEM_HIGH
    }

    /** HVAC treats 0 / invalid like off for display and cycle. */
    private fun normalizeOem(raw: Int?): Int = when (raw) {
        null -> OEM_OFF
        in SENTINELS -> OEM_OFF
        0 -> OEM_OFF
        else -> raw
    }

    private fun readOem(kind: Kind, zone: Zone): Int? {
        // Shark / non-L1P passenger: HVAC reads NotL1P getters (area-2 get stays OFF).
        if (zone == Zone.PASSENGER) {
            val notL1pName =
                if (kind == Kind.HEAT) "getSeatHeatingNotL1PState"
                else "getSeatVentilatingNotL1PState"
            val notL1p = getIntNoArg(settingDevice, notL1pName)
            Log.i(TAG, "read PASSENGER ${kind.name} $notL1pName=$notL1p")
            // Valid OEM levels only; 0/missing → fall back to area-2 get (L1P cars).
            if (notL1p in 1..4) return notL1p
        }

        val name = if (kind == Kind.HEAT) "getSeatHeatingState" else "getSeatVentilatingState"
        val raw = getIntArg(settingDevice, name, zone.area) ?: return null
        Log.i(TAG, "read ${zone.name} ${kind.name} $name(${zone.area})=$raw")
        return raw.takeUnless { it in SENTINELS }
    }

    private fun writeOem(kind: Kind, zone: Zone, oem: Int): CommandResult {
        // HVAC passenger NotL1P path still writes setSeat*(2, state) (presenter j0/k0 → s4/t4).
        val name = if (kind == Kind.HEAT) "setSeatHeatingState" else "setSeatVentilatingState"
        return call(settingDevice, name, zone.area, oem)
    }

    private fun ensureDevice(): Boolean = isBound()

    private fun notBound(): CommandResult =
        CommandResult(false, "seat", null, lastBindError ?: "not bound")

    private fun loadInstance(className: String): Any? {
        return try {
            val cls = Class.forName(className)
            val method = cls.getMethod("getInstance", Context::class.java)
            invokeGetInstance(method, permContext) ?: invokeGetInstance(method, appContext)
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            lastBindError = "${c.javaClass.simpleName}: ${c.message}"
            Log.w(TAG, "loadInstance($className): $lastBindError")
            null
        }
    }

    private fun invokeGetInstance(method: java.lang.reflect.Method, ctx: Context): Any? {
        return try {
            method.invoke(null, ctx)
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            lastBindError = "${c.javaClass.simpleName}: ${c.message}"
            null
        }
    }

    private fun getIntArg(dev: Any?, name: String, arg: Int): Int? {
        if (dev == null) return null
        return runCatching {
            (dev.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(dev, arg) as? Number)
                ?.toInt()
        }.getOrNull()
    }

    private fun getIntNoArg(dev: Any?, name: String): Int? {
        if (dev == null) return null
        return runCatching {
            (dev.javaClass.getMethod(name).invoke(dev) as? Number)?.toInt()
        }.getOrNull()
    }

    private fun call(dev: Any?, name: String, vararg args: Any): CommandResult {
        if (dev == null) return CommandResult(false, name, null, "no device")
        val types = args.map { argType(it) }.toTypedArray()
        return try {
            val method = dev.javaClass.getMethod(name, *types)
            val boxed = method.invoke(dev, *args)
            val raw = (boxed as? Number)?.toInt()
            CommandResult(
                success = raw == null || raw >= 0,
                method = "$name(${args.joinToString()})",
                raw = raw,
                detail = "code=$raw"
            )
        } catch (e: NoSuchMethodException) {
            CommandResult(false, name, null, "no such method")
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            CommandResult(false, name, null, "${c.javaClass.simpleName}: ${c.message}")
        }
    }

    private fun pause(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    companion object {
        private const val TAG = "BydSeatController"
        private const val SETTING_CLASS = "android.hardware.bydauto.setting.BYDAutoSettingDevice"

        /** BYDAutoSettingDevice.SEAT_HEATING_OFF / SEAT_VENTILATING_OFF */
        private const val OEM_OFF = 1
        /** BYDAutoSettingDevice.SEAT_HEATING_LOW */
        private const val OEM_LOW = 2
        /** BYDAutoSettingDevice.SEAT_HEATING_HIGH */
        private const val OEM_HIGH = 3

        private val SENTINELS = setOf(
            -1,
            -2147482645,
            -2147482646,
            -2147482647,
            -2147482648,
            65535,
        )

        private fun argType(arg: Any): Class<*> = when (arg) {
            is Int -> Int::class.javaPrimitiveType!!
            is IntArray -> IntArray::class.java
            is String -> String::class.java
            else -> arg.javaClass
        }
    }
}
