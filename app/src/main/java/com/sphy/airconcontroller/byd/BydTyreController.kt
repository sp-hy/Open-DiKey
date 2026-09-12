package com.sphy.airconcontroller.byd

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * Reflective client for `android.hardware.bydauto.tyre.BYDAutoTyreDevice` (TPMS).
 *
 * Corner areas are 1=FL, 2=FR, 3=RL, 4=RR (not 0-based).
 *
 * Pressure: prefer [getTyrePressureValueByType] — `0` means no calibrated reading for
 * that corner (do not fall back to [getTyrePressureValue], which can be stale).
 * Physical kPa from [getTyrePressureValue] is used only when byType > 0.
 *
 * Temperature: OEM raw is offset by [TEMP_OFFSET_C] (°C); the getter is not per-corner.
 */
class BydTyreController(context: Context) {
    private val appContext = context.applicationContext
    private val permContext = BydPermissionContext(appContext)

    @Volatile private var tyreDevice: Any? = null
    @Volatile private var tyreClass: Class<*>? = null

    @Volatile
    var lastBindError: String? = null
        private set

    enum class Corner(val field: String, val label: String, val area: Int) {
        LEFT_FRONT("TYRE_COMMAND_AREA_LEFT_FRONT", "FL", 1),
        RIGHT_FRONT("TYRE_COMMAND_AREA_RIGHT_FRONT", "FR", 2),
        LEFT_REAR("TYRE_COMMAND_AREA_LEFT_REAR", "RL", 3),
        RIGHT_REAR("TYRE_COMMAND_AREA_RIGHT_REAR", "RR", 4),
    }

    data class CornerReading(
        val corner: Corner,
        val area: Int,
        val pressureKpa: Int?,
        val pressureByType: Float?,
        val temperature: Int?,
        val pressureState: String,
        val airLeakState: String,
        val signalState: String,
    ) {
        fun pressureLabel(): String {
            val byType = pressureByType
            if (byType == null || byType <= 0f) return "0.0 psi"
            val kpa = pressureKpa
            if (kpa != null && kpa > 0) {
                return String.format("%.1f psi", kpa * KPA_TO_PSI)
            }
            return String.format("%.1f psi", byType / 10f)
        }

        fun temperatureLabel(): String {
            if (temperature == null) return "—"
            return "${temperature - TEMP_OFFSET_C}°C"
        }

        /** Compact psi label for the tyre strip (1 decimal + unit). */
        fun pressurePsiShort(): String {
            val byType = pressureByType
            if (byType == null || byType <= 0f) return "—"
            val psi = if (pressureKpa != null && pressureKpa > 0) {
                pressureKpa * KPA_TO_PSI
            } else {
                byType / 10f
            }
            return String.format("%.1f psi", psi)
        }

        /**
         * Visual health: [OK] green, [UNDER] orange, [FAULT] red
         * (over-pressure, leak, signal error, or uncalibrated).
         */
        fun statusTone(): StatusTone {
            val byType = pressureByType
            if (byType == null || byType <= 0f) return StatusTone.FAULT
            if (signalState != "ok") return StatusTone.FAULT
            if (airLeakState != "ok") return StatusTone.FAULT
            if (pressureState == "over") return StatusTone.FAULT
            if (pressureState == "under") return StatusTone.UNDER
            return StatusTone.OK
        }
    }

    enum class StatusTone { OK, UNDER, FAULT }

    data class TyreSnapshot(
        val sdkInjected: Boolean,
        val bound: Boolean,
        val bindError: String?,
        val corners: List<CornerReading>,
        val batteryState: String?,
        val systemState: String?,
        val temperatureState: String?,
    ) {
        fun toDisplayString(): String = buildString {
            append("SDK injected: ").append(sdkInjected)
            append("\nBound: ").append(bound)
            if (!bindError.isNullOrBlank()) append("\nBind error: ").append(bindError)
            if (!bound) return@buildString

            append("\n\nTyres")
            for (c in corners) {
                append('\n')
                append(c.corner.label)
                append("  ").append(c.pressureLabel())
                append("  ").append(c.temperatureLabel())
                append("  press=").append(c.pressureState)
                append("  leak=").append(c.airLeakState)
                append("  signal=").append(c.signalState)
            }
            append("\n\nTPMS system=").append(systemState ?: "—")
            append("  battery=").append(batteryState ?: "—")
            append("  tempState=").append(temperatureState ?: "—")
        }
    }

    fun bind(): Boolean {
        if (tyreDevice != null) return true
        lastBindError = null

        val injected = Dilink5SdkInjector.ensure(appContext)
        if (!injected && !Dilink5SdkInjector.isLoadable(appContext)) {
            lastBindError =
                "Could not inject com.byd.data.collect (hidden-API exemption missing, or OEM app not installed)"
            Log.w(TAG, "bind: $lastBindError")
            return false
        }

        tyreDevice = loadInstance(TYRE_CLASS)
        val ok = tyreDevice != null
        if (!ok && lastBindError == null) lastBindError = "BYDAutoTyreDevice unavailable"
        Log.i(TAG, "bind tyre=${tyreDevice != null}")
        return ok
    }

    fun isBound(): Boolean = tyreDevice != null || bind()

    fun snapshot(): TyreSnapshot {
        val injected = Dilink5SdkInjector.isLoadable(appContext)
        if (!isBound()) {
            return TyreSnapshot(
                sdkInjected = injected,
                bound = false,
                bindError = lastBindError,
                corners = emptyList(),
                batteryState = null,
                systemState = null,
                temperatureState = null,
            )
        }
        val corners = Corner.entries.map { corner ->
            val area = areaOf(corner)
            val kpa = getIntArg("getTyrePressureValue", area)
            val byType = getFloatArg("getTyrePressureValueByType", area)
            val temp = getIntArg("getTyreTemperatureValue", area)
            Log.i(
                TAG,
                "${corner.label} area=$area kpa=$kpa byType=$byType temp=$temp " +
                    "press=${getIntArg("getTyrePressureState", area)} " +
                    "leak=${getIntArg("getTyreAirLeakState", area)} " +
                    "sig=${getIntArg("getTyreSignalState", area)}"
            )
            CornerReading(
                corner = corner,
                area = area,
                pressureKpa = kpa?.takeUnless { it in SENTINELS },
                pressureByType = byType?.takeUnless { it.isNaN() || it < 0f },
                temperature = temp?.takeUnless { it in SENTINELS },
                pressureState = decode(
                    getIntArg("getTyrePressureState", area),
                    PRESSURE_STATE,
                ),
                airLeakState = decode(getIntArg("getTyreAirLeakState", area), AIR_LEAK_STATE),
                signalState = decode(getIntArg("getTyreSignalState", area), SIGNAL_STATE),
            )
        }
        return TyreSnapshot(
            sdkInjected = injected,
            bound = true,
            bindError = null,
            corners = corners,
            batteryState = decode(getIntNoArg("getTyreBatteryState"), BATTERY_STATE),
            systemState = decode(getIntNoArg("getTyreSystemState"), SYSTEM_STATE),
            temperatureState = decode(getIntNoArg("getTyreTemperatureState"), TEMP_STATE),
        )
    }

    fun statusLine(): String = snapshot().toDisplayString()

    fun dumpMethods(): String {
        if (!isBound()) return "not bound: ${lastBindError ?: "—"}"
        val cls = tyreDevice!!.javaClass
        return buildString {
            appendLine("class ${cls.name}")
            appendLine("--- live reads ---")
            for (corner in Corner.entries) {
                val area = areaOf(corner)
                appendLine(
                    "${corner.label} area=$area " +
                        "kpa=${getIntArg("getTyrePressureValue", area)} " +
                        "byType=${getFloatArg("getTyrePressureValueByType", area)} " +
                        "temp=${getIntArg("getTyreTemperatureValue", area)} " +
                        "press=${getIntArg("getTyrePressureState", area)} " +
                        "leak=${getIntArg("getTyreAirLeakState", area)} " +
                        "sig=${getIntArg("getTyreSignalState", area)}"
                )
            }
            appendLine(
                "system=${getIntNoArg("getTyreSystemState")} " +
                    "battery=${getIntNoArg("getTyreBatteryState")} " +
                    "tempState=${getIntNoArg("getTyreTemperatureState")}"
            )
            appendLine("--- fields ---")
            cls.fields
                .filter { it.name.startsWith("TYRE_") }
                .sortedBy { it.name }
                .forEach { f ->
                    val v = runCatching { f.get(null) }.getOrNull()
                    appendLine("${f.name} = $v")
                }
            appendLine("--- methods ---")
            cls.methods
                .filter { it.declaringClass == cls || it.name.contains("Tyre", ignoreCase = true) }
                .sortedBy { it.name }
                .forEach { m ->
                    appendLine(
                        "${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})"
                    )
                }
        }
    }

    private fun areaOf(corner: Corner): Int =
        constInt(corner.field) ?: corner.area

    private fun decode(raw: Int?, known: Map<Int, String>): String {
        if (raw == null) return "—"
        if (raw in SENTINELS) return "invalid"
        return known[raw] ?: "raw=$raw"
    }

    private fun constInt(name: String): Int? {
        val cls = tyreClass ?: return null
        return runCatching { cls.getField(name).getInt(null) }.getOrNull()
    }

    private fun loadInstance(className: String): Any? {
        return try {
            val cls = Class.forName(className)
            tyreClass = cls
            val method = cls.getMethod("getInstance", Context::class.java)
            // Prefer real app context: OEM getInstance() stores context.getApplicationContext(),
            // and tyre perms are already ADB-granted on this package.
            invokeGetInstance(method, appContext) ?: invokeGetInstance(method, permContext)
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

    private fun getIntArg(name: String, arg: Int): Int? {
        val dev = tyreDevice ?: return null
        return try {
            (dev.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(dev, arg) as? Number)
                ?.toInt()
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            Log.w(TAG, "$name($arg): ${c.javaClass.simpleName}: ${c.message}")
            null
        }
    }

    private fun getFloatArg(name: String, arg: Int): Float? {
        val dev = tyreDevice ?: return null
        return try {
            (dev.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(dev, arg) as? Number)
                ?.toFloat()
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            Log.w(TAG, "$name($arg): ${c.javaClass.simpleName}: ${c.message}")
            null
        }
    }

    private fun getIntNoArg(name: String): Int? {
        val dev = tyreDevice ?: return null
        return try {
            (dev.javaClass.getMethod(name).invoke(dev) as? Number)?.toInt()
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            Log.w(TAG, "$name(): ${c.javaClass.simpleName}: ${c.message}")
            null
        }
    }

    companion object {
        private const val TAG = "BydTyreController"
        private const val TYRE_CLASS = "android.hardware.bydauto.tyre.BYDAutoTyreDevice"
        private const val KPA_TO_PSI = 0.1450377377f
        /** OEM tyre temp raw uses the documented −40…369.4 °C span (raw 0 ⇒ −40 °C). */
        private const val TEMP_OFFSET_C = 40

        // From OEM BYDAutoTyreDevice (data-collect / caradapter).
        private val PRESSURE_STATE = mapOf(0 to "normal", 1 to "over", 2 to "under")
        private val AIR_LEAK_STATE = mapOf(0 to "ok", 1 to "quick", 2 to "slow")
        private val SIGNAL_STATE = mapOf(0 to "ok", 1 to "error")
        private val BATTERY_STATE = mapOf(0 to "normal", 1 to "low")
        private val SYSTEM_STATE = mapOf(
            0 to "normal",
            1 to "self-check",
            2 to "signal-anomaly",
            3 to "fault",
            4 to "masked",
        )
        private val TEMP_STATE = mapOf(
            0 to "normal",
            1 to "super-high",
            2 to "high",
            3 to "sleep",
        )

        private val SENTINELS = setOf(
            -1,
            -2147482645,
            -2147482646,
            -2147482647,
            -2147482648,
            65535,
        )
    }
}
