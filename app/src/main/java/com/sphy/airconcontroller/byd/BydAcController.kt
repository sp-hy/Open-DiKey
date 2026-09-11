package com.sphy.airconcontroller.byd

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * Reflective client for `android.hardware.bydauto.ac.BYDAutoAcDevice`.
 *
 * Method names follow the public BYD Auto SDK / on-car dumps (note the `Temprature` typo).
 * Several SET overloads are tried because DiLink 3 vs 5 signatures drift.
 */
class BydAcController(context: Context) {
    private val appContext = context.applicationContext
    private val permContext = BydPermissionContext(appContext)

    @Volatile
    private var device: Any? = null

    @Volatile
    private var lastSetFanLevel: Int? = null

    @Volatile
    private var fanSetter: ((Int) -> CommandResult)? = null

    @Volatile
    private var lastSetCycleMode: Int? = null

    @Volatile
    private var cycleSetter: ((Int) -> CommandResult)? = null

    @Volatile
    private var frontDefrostSetter: ((Int) -> CommandResult)? = null

    @Volatile
    private var rearDefrostSetter: ((Int) -> CommandResult)? = null

    @Volatile
    private var bodywork: Any? = null

    @Volatile
    private var lastSetRearHeat: Boolean? = null

    @Volatile
    private var lastSetVentilation: Boolean? = null

    @Volatile
    private var lastSetTempSynced: Boolean? = null

    @Volatile
    private var lastSetWindMode: Int? = null

    @Volatile
    var lastBindError: String? = null
        private set

    data class CommandResult(
        val success: Boolean,
        val method: String,
        val raw: Int?,
        val detail: String
    )

    data class AcSnapshot(
        val sdkInjected: Boolean,
        val bound: Boolean,
        val bindError: String?,
        val powerOn: Boolean?,
        val driverTempC: Int?,
        val passengerTempC: Int?,
        val outsideTempC: Int?,
        val fanLevel: Int?,
        val controlMode: Int?,
        val cycleMode: Int?,
        val compressorMode: Int?,
        val maxCool: Boolean?,
        val ventilation: Boolean?,
        val frontDefrost: Boolean?,
        val rearDefrost: Boolean?,
        /** True when driver/passenger setpoints are linked (SEPARATE_OFF). */
        val tempSynced: Boolean?
    ) {
        fun toDisplayString(): String {
            if (!sdkInjected) {
                return "OEM SDK not loaded. Grant ADB + hidden-API access, then reopen the app.\n" +
                    (bindError?.let { "Detail: $it" } ?: "")
            }
            if (!bound) {
                return "AC device not bound.\n" + (bindError ?: "getInstance returned null")
            }
            return buildString {
                appendLine("AC: ${fmtOnOff(powerOn)}")
                appendLine("Driver set: ${fmtTemp(driverTempC)}")
                appendLine("Passenger set: ${fmtTemp(passengerTempC)}")
                appendLine("Outside: ${fmtTemp(outsideTempC)}")
                appendLine("Fan: ${fanLevel?.toString() ?: "—"}")
                appendLine("Mode: ${fmtControl(controlMode)}")
                appendLine("Temp sync: ${fmtOnOff(tempSynced)}")
                appendLine("Air: ${fmtCycle(cycleMode)}")
                appendLine("Front demist: ${fmtOnOff(frontDefrost)}")
                appendLine("Rear window and mirrors: ${fmtOnOff(rearDefrost)}")
                appendLine("Air-only: ${fmtOnOff(ventilation)}")
                appendLine("Max cool: ${fmtOnOff(maxCool)}")
            }.trimEnd()
        }
    }

    fun bind(): Boolean {
        lastBindError = null
        if (device != null) return true

        val injected = Dilink5SdkInjector.ensure(appContext)
        if (!injected && !Dilink5SdkInjector.isLoadable(appContext)) {
            lastBindError = "Could not inject com.byd.data.collect (hidden-API exemption missing, or OEM app not installed)"
            return false
        }

        return try {
            val cls = Class.forName(AC_CLASS)
            val method = cls.getMethod("getInstance", Context::class.java)
            val inst = invokeGetInstance(method, permContext) ?: invokeGetInstance(method, appContext)
            if (inst == null) {
                if (lastBindError == null) lastBindError = "BYDAutoAcDevice.getInstance returned null"
                false
            } else {
                device = inst
                lastBindError = null
                Log.i(TAG, "bound BYDAutoAcDevice via ${inst.javaClass.name}")
                true
            }
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            lastBindError = "${c.javaClass.simpleName}: ${c.message}"
            Log.w(TAG, "bind failed: $lastBindError")
            false
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

    fun isBound(): Boolean = device != null || bind()

    fun snapshot(): AcSnapshot {
        val injected = Dilink5SdkInjector.isLoadable(appContext) || Dilink5SdkInjector.ensure(appContext)
        val ok = isBound()
        val dev = device
        return AcSnapshot(
            sdkInjected = injected,
            bound = ok && dev != null,
            bindError = lastBindError,
            powerOn = intToBool(getInt("getAcStartState")),
            driverTempC = plausibleTemp(getIntArg("getTemprature", ZONE_DRIVER) ?: getIntArg("getTemperature", ZONE_DRIVER)),
            passengerTempC = plausibleTemp(getIntArg("getTemprature", ZONE_PASSENGER) ?: getIntArg("getTemperature", ZONE_PASSENGER)),
            outsideTempC = plausibleTemp(getIntArg("getTemprature", ZONE_OUTSIDE) ?: getIntArg("getTemperature", ZONE_OUTSIDE)),
            fanLevel = lastSetFanLevel ?: getInt("getAcWindLevel"),
            controlMode = getInt("getAcControlMode"),
            cycleMode = getInt("getAcCycleMode") ?: lastSetCycleMode,
            compressorMode = getInt("getAcCompressorMode"),
            maxCool = intToBool(getInt("getAcMaxCoolingState")),
            ventilation = lastSetVentilation ?: intToBool(getInt("getAcVentilationState")),
            frontDefrost = intToBool(defrostState(frontDefrostArea())),
            rearDefrost = lastSetRearHeat ?: intToBool(rearHeatState()),
            tempSynced = isTempSynced(),
        )
    }

    fun start(): CommandResult {
        if (!ensureDevice()) return notBound()
        return firstSuccess(
            { call("start", 0) },
            { call("start", 1) },
            { call("setAcStartState", AC_POWER_ON) },
            { call("setAcStartState", AC_POWER_ON, 0) },
        )
    }

    fun stop(): CommandResult {
        if (!ensureDevice()) return notBound()
        return firstSuccess(
            { call("stop", 0) },
            { call("stop", 1) },
            { call("setAcStartState", AC_POWER_OFF) },
            { call("setAcStartState", AC_POWER_OFF, 0) },
        )
    }

    fun setDriverTemp(celsius: Int): CommandResult = setZoneTemp(ZONE_DRIVER, celsius)

    fun setPassengerTemp(celsius: Int): CommandResult {
        ensureDualZone()
        return setZoneTemp(ZONE_PASSENGER, celsius)
    }

    fun nudgeDriverTemp(delta: Int): CommandResult {
        val current = snapshot().driverTempC ?: 22
        return setDriverTemp(current + delta)
    }

    fun nudgePassengerTemp(delta: Int): CommandResult {
        val current = snapshot().passengerTempC ?: snapshot().driverTempC ?: 22
        return setPassengerTemp(current + delta)
    }

    private fun setZoneTemp(zone: Int, celsius: Int): CommandResult {
        if (!ensureDevice()) return notBound()
        val temp = celsius.coerceIn(TEMP_MIN, TEMP_MAX)
        return firstSuccess(
            { call("setAcTemperature", zone, temp, SOURCE_VOICE, 1) },
            { call("setAcTemprature", zone, temp, SOURCE_VOICE, 1) },
            { call("setAcTemperature", zone, temp, SOURCE_UI, 0) },
            { call("setTemprature", zone, temp) },
            { call("setTemperature", zone, temp) },
        )
    }

    /**
     * Toggle driver/passenger temperature link (climate SYNC).
     * Synced = SEPARATE_OFF; dual-zone = SEPARATE_ON.
     */
    fun toggleSync(): CommandResult {
        if (!ensureDevice()) return notBound()
        val synced = isTempSynced() ?: false
        return setTempSynced(!synced)
    }

    fun setTempSynced(synced: Boolean): CommandResult {
        if (!ensureDevice()) return notBound()
        val mode = if (synced) tempCtrlSynced() else tempCtrlSeparate()
        val attempts = listOf(
            { call("setAcTemperatureControlMode", SOURCE_VOICE, mode) },
            { call("setAcTemperatureControlMode", SOURCE_UI, mode) },
            { call("setAcTemperatureControlMode", mode, SOURCE_VOICE) },
            { call("setAcTemperatureControlMode", mode) },
        )
        var last = notBound()
        for (attempt in attempts) {
            val result = attempt()
            pauseForEcu()
            val after = getInt("getAcTemperatureControlMode")
            if (result.success && (after == mode || after == null)) {
                lastSetTempSynced = synced
                return result
            }
            if (result.detail != "no such method") last = result
        }
        return last
    }

    private fun ensureDualZone() {
        if (!ensureDevice()) return
        if (isTempSynced() == false) return
        setTempSynced(false)
    }

    private fun isTempSynced(): Boolean? {
        val mode = getInt("getAcTemperatureControlMode")
        if (mode != null) {
            val synced = tempCtrlSynced()
            val separate = tempCtrlSeparate()
            when (mode) {
                synced -> {
                    lastSetTempSynced = true
                    return true
                }
                separate -> {
                    lastSetTempSynced = false
                    return false
                }
            }
        }
        return lastSetTempSynced
    }

    private fun tempCtrlSynced(): Int =
        constInt(
            "AC_TEMPCTRL_SEPARATE_OFF",
            "AC_TEMPCTRLMODE_SEPARATE_OFF",
            "AC_TEMPERATURE_SYNC",
            "AC_TEMPCTRL_SYNC",
        ) ?: TEMP_CTRL_SYNCED

    private fun tempCtrlSeparate(): Int =
        constInt(
            "AC_TEMPCTRL_SEPARATE_ON",
            "AC_TEMPCTRLMODE_SEPARATE",
            "AC_TEMPCTRLMODE_SEPARATE_ON",
            "AC_TEMPERATURE_DUAL",
            "AC_TEMPCTRL_DUAL",
            "AC_TEMPCTRLMODE_DUAL",
            "AC_CTRL_TEMP_DUAL",
        ) ?: TEMP_CTRL_SEPARATE

    fun setFanLevel(level: Int): CommandResult {
        if (!ensureDevice()) return notBound()
        val lv = level.coerceIn(FAN_MIN, FAN_MAX)
        // Only leave Auto when we actually are in Auto. Re-sending Manual on every tap
        // makes the car reset fan to 1, then we write the real level (the visible flicker).
        if (getInt("getAcControlMode") == CONTROL_AUTO) {
            setAuto(false)
        }

        val cached = fanSetter
        if (cached != null) {
            val repeated = cached(lv)
            if (repeated.success) {
                lastSetFanLevel = lv
                return repeated
            }
            fanSetter = null
        }

        val attempts: List<(Int) -> CommandResult> = listOf(
            { v -> call("set", DEVICE_TYPE_AC, FEATURE_WIND_LEVEL, v) },
            { v -> call("set", intArrayOf(FEATURE_WIND_LEVEL), intArrayOf(v)) },
            { v -> call("set", FEATURE_WIND_LEVEL, v) },
            { v -> setViaDeviceManager(FEATURE_WIND_LEVEL, v) },
            { v -> call("setAcWindLevel", SOURCE_VOICE, v) },
            { v -> call("setAcWindLeve", SOURCE_VOICE, v) },
            { v -> call("setAcWindLevel", SOURCE_UI, v) },
            { v -> call("setAcWindLevel", v) },
        )
        var last = notBound()
        for (attempt in attempts) {
            val result = attempt(lv)
            if (result.success) {
                fanSetter = attempt
                lastSetFanLevel = lv
                return result
            }
            if (result.detail != "no such method") last = result
        }
        return last
    }

    fun nudgeFan(delta: Int): CommandResult {
        val next = (currentFanLevel() + delta).coerceIn(FAN_MIN, FAN_MAX)
        return setFanLevel(next)
    }

    private fun currentFanLevel(): Int {
        lastSetFanLevel?.takeIf { it in FAN_MIN..FAN_MAX }?.let { return it }
        getInt("getAcWindLevel")?.takeIf { it in FAN_MIN..FAN_MAX }?.let { return it }
        return 3
    }

    fun toggleAuto(): CommandResult {
        if (!ensureDevice()) return notBound()
        val auto = constInt("AC_CTRLMODE_AUTO", "AC_CONTROLMODE_AUTO", "AC_CTRL_MODE_AUTO") ?: CONTROL_AUTO
        val currentlyAuto = getInt("getAcControlMode") == auto
        return setAuto(!currentlyAuto)
    }

    fun setAuto(enabled: Boolean): CommandResult {
        if (!ensureDevice()) return notBound()
        val auto = constInt("AC_CTRLMODE_AUTO", "AC_CONTROLMODE_AUTO", "AC_CTRL_MODE_AUTO") ?: CONTROL_AUTO
        val manual = constInt("AC_CTRLMODE_MANUAL", "AC_CONTROLMODE_MANUAL", "AC_CTRL_MODE_MANUAL") ?: CONTROL_MANUAL
        val mode = if (enabled) auto else manual
        // Same (source, value) order as cycle. (mode, SOURCE_VOICE=1) writes manual.
        val attempts = listOf(
            { call("setAcControlMode", SOURCE_VOICE, mode) },
            { call("setAcControlMode", SOURCE_UI, mode) },
        )
        var last = notBound()
        for (attempt in attempts) {
            val result = attempt()
            pauseForEcu()
            val after = getInt("getAcControlMode")
            if (result.success && (after == mode || after == null)) return result
            if (result.detail != "no such method") last = result
        }
        return last
    }

    fun toggleRecirc(): CommandResult {
        if (!ensureDevice()) return notBound()
        val before = getInt("getAcCycleMode") ?: lastSetCycleMode
        val recirc = cycleValue(recirc = true)
        val fresh = cycleValue(recirc = false)
        val primary = when (before) {
            recirc -> fresh
            fresh -> recirc
            else -> if (before == CYCLE_RECIRC) fresh else recirc
        }
        val targets = listOf(primary, 0, 1, 2).distinct().filter { it != before }
        var last = notBound()
        for (target in targets) {
            val result = setCycleMode(target, before)
            if (result.success) return result
            last = result
        }
        return last
    }

    private fun setCycleMode(mode: Int, before: Int?): CommandResult {
        val cached = cycleSetter
        if (cached != null) {
            val repeated = cached(mode)
            if (repeated.success && cycleReadback(mode, before)) {
                lastSetCycleMode = mode
                return repeated
            }
            cycleSetter = null
        }

        // DiLink 5 named 2-arg setters are (source, value). (mode, SOURCE_VOICE=1)
        // always wrote 1, so recirc stuck and fresh never applied.
        val attempts: List<(Int) -> CommandResult> = listOf(
            { v -> call("setAcCycleMode", SOURCE_VOICE, v) },
            { v -> call("setAcCycleMode", SOURCE_UI, v) },
            { v -> call("setAcCycleMode", v, SOURCE_UI) },
            { v -> call("setAcCycleMode", v, SOURCE_VOICE) },
            { v -> call("setAcCycleMode", v) },
        )
        var last = notBound()
        for (attempt in attempts) {
            val result = attempt(mode)
            if (result.success && cycleReadback(mode, before)) {
                cycleSetter = attempt
                lastSetCycleMode = mode
                return result
            }
            if (result.detail != "no such method") last = result
        }
        return last
    }

    private fun cycleReadback(target: Int, @Suppress("UNUSED_PARAMETER") before: Int?): Boolean {
        pauseForEcu()
        return getInt("getAcCycleMode") == target
    }

    fun toggleFrontDefrost(): CommandResult = toggleWindshieldDefrost()

    /** Rear window heater and wing-mirror heaters are one OEM switch. */
    fun toggleRearWindowHeat(): CommandResult {
        if (!ensureDevice()) return notBound()
        val currentlyOn = lastSetRearHeat ?: false
        val next = if (currentlyOn) 0 else 1
        val area = constInt("AC_DEFROST_AREA_REAR") ?: 2
        // Front demist uses (SOURCE_UI, area, state). The same with SOURCE_UI + rear area
        // pulses the windscreen. The shotgun that actually started rear/mirrors was the
        // next call: (SOURCE_VOICE, rear area, state). Electric-only does not drive this car.
        call("setElectricDefrostState", next)
        val result = call("setAcDefrostState", SOURCE_VOICE, area, next)
        if (result.success) lastSetRearHeat = next == 1
        return result
    }

    private fun toggleWindshieldDefrost(): CommandResult {
        if (!ensureDevice()) return notBound()
        val area = frontDefrostArea()
        val current = defrostState(area)
        val next = if (current == 1) 0 else 1

        val cached = frontDefrostSetter
        if (cached != null) {
            val repeated = cached(next)
            if (repeated.success && (defrostState(area) == null || defrostState(area) == next)) {
                return repeated
            }
            frontDefrostSetter = null
        }

        val attempts = mutableListOf<(Int) -> CommandResult>()
        attempts += { v -> call("setAcDefrostState", SOURCE_UI, area, v) }
        attempts += { v -> call("setAcDefrostState", SOURCE_VOICE, area, v) }
        attempts += { v -> call("setAcDefrostState", area, v, SOURCE_UI) }
        attempts += { v -> call("setAcDefrostState", area, v, SOURCE_VOICE) }
        attempts += { v -> call("setAcDefrostState", SOURCE_UI, v) }
        attempts += { v -> call("setAcDefrostState", SOURCE_VOICE, v) }
        attempts += { v -> call("setAcDefrostState", v) }
        if (next == 1) {
            attempts += { _ -> call("setAcWindMode", SOURCE_VOICE, WIND_DEFROST) }
            attempts += { _ -> call("setAcWindMode", SOURCE_UI, WIND_DEFROST) }
            attempts += { _ -> call("setAcWindMode", WIND_DEFROST, SOURCE_VOICE) }
        }

        var last = notBound()
        for (attempt in attempts) {
            val result = attempt(next)
            pauseForEcu()
            if (result.success && (defrostState(area) == null || defrostState(area) == next)) {
                frontDefrostSetter = attempt
                return result
            }
            if (result.detail != "no such method") last = result
        }
        return last
    }

    private fun rearHeatState(): Int? {
        getInt("getElectricDefrostState")?.let { return it }
        getInt("getAcRearDefrostState")?.let { return it }
        getInt("getRearDefrostState")?.let { return it }
        getInt("getDefrostRearState")?.let { return it }
        return defrostState(rearDefrostArea())
    }

    private fun rearHeatReadback(target: Int, frontBefore: Int?, windBefore: Int?): Boolean {
        pauseForEcu()
        val frontAfter = defrostState(frontDefrostArea())
        val windAfter = getInt("getAcWindMode")
        val hitWindshield = (frontBefore != null && frontAfter != null && frontBefore != frontAfter) ||
            (windBefore != WIND_DEFROST && windAfter == WIND_DEFROST)
        if (hitWindshield) return false
        val after = rearHeatState()
        return after == target || after == null
    }

    fun toggleAirOnly(): CommandResult {
        if (!ensureDevice()) return notBound()
        val before = getInt("getAcVentilationState")
        val currentlyOn = intToBool(before) ?: lastSetVentilation ?: false
        val next = if (currentlyOn) 0 else 1
        // (state, SOURCE_VOICE=1) always writes on, so off never applied.
        val attempts = listOf(
            { call("setAcVentilationState", SOURCE_VOICE, next) },
            { call("setAcVentilationState", SOURCE_UI, next) },
        )
        var last = notBound()
        for (attempt in attempts) {
            val result = attempt()
            pauseForEcu()
            val after = getInt("getAcVentilationState")
            if (result.success && (after == next || after == null)) {
                lastSetVentilation = next == 1
                return result
            }
            if (result.detail != "no such method") last = result
        }
        val compressorTarget = if (next == 1) 0 else 1
        val viaCompressor = firstSuccess(
            { call("setAcCompressorMode", SOURCE_VOICE, compressorTarget) },
            { call("setAcCompressorMode", SOURCE_UI, compressorTarget) },
        )
        if (viaCompressor.success) lastSetVentilation = next == 1
        return if (viaCompressor.success) viaCompressor else last
    }

    fun setMaxCool(on: Boolean): CommandResult {
        if (!ensureDevice()) return notBound()
        val state = if (on) 1 else 0
        return firstSuccess(
            { call("setAcMaxCoolingState", state) },
            { call("setAcMaxCoolingState", state, SOURCE_VOICE) },
        )
    }

    fun togglePower(): CommandResult {
        val on = snapshot().powerOn ?: false
        return if (on) stop() else start()
    }

    /** Compressor A/C, distinct from climate power and air-only/ventilation. */
    fun toggleCompressor(): CommandResult {
        if (!ensureDevice()) return notBound()
        val current = getInt("getAcCompressorMode") ?: 1
        val next = if (current == 1) 0 else 1
        return firstSuccess(
            { call("setAcCompressorMode", SOURCE_VOICE, next) },
            { call("setAcCompressorMode", SOURCE_UI, next) },
            { call("setAcCompressorMode", next, SOURCE_VOICE) },
            { call("setAcCompressorMode", next) },
        )
    }

    fun toggleMaxCool(): CommandResult {
        val on = snapshot().maxCool ?: false
        return setMaxCool(!on)
    }

    /**
     * Face → face+foot → foot → foot+demist. Skips full defrost (that's btn6).
     * Values come from OEM constants when present; otherwise 1..4.
     */
    fun cycleWindDirection(): CommandResult {
        if (!ensureDevice()) return notBound()
        val current = getInt("getAcWindMode") ?: lastSetWindMode
        val modes = windDirectionCycle()
        val idx = modes.indexOf(current)
        val next = modes[(if (idx < 0) 0 else idx + 1) % modes.size]
        val result = firstSuccess(
            { call("setAcWindMode", SOURCE_VOICE, next) },
            { call("setAcWindMode", SOURCE_UI, next) },
            { call("setAcWindMode", next, SOURCE_VOICE) },
            { call("setAcWindMode", next) },
        )
        if (result.success) {
            lastSetWindMode = next
            pauseForEcu()
        }
        return result
    }

    private fun windDirectionCycle(): List<Int> {
        val named = listOfNotNull(
            constInt("AC_WINDMODE_FACE", "AC_WIND_FACE", "WIND_FACE"),
            constInt(
                "AC_WINDMODE_FACE_FOOT",
                "AC_WINDMODE_FACEANDFOOT",
                "AC_WINDMODE_FACEFOOT",
                "AC_WIND_FACE_FOOT",
                "WIND_FACE_FOOT"
            ),
            constInt("AC_WINDMODE_FOOT", "AC_WIND_FOOT", "WIND_FOOT"),
            constInt(
                "AC_WINDMODE_FOOT_DEFROST",
                "AC_WINDMODE_FOOTANDDEFROST",
                "AC_WIND_FOOT_DEFROST"
            ),
        ).distinct().filter { it != WIND_DEFROST }
        return named.ifEmpty { listOf(1, 2, 3, 4) }
    }

    fun dumpMethods(): String {
        if (!ensureDevice()) return lastBindError ?: "AC device not bound"
        val methods = device!!.javaClass.methods
            .filter { it.declaringClass.name.contains("bydauto") || it.name.startsWith("get") || it.name.startsWith("set") || it.name == "start" || it.name == "stop" }
            .sortedBy { it.name }
        val methodText = methods.joinToString("\n") { m ->
            "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}):${m.returnType.simpleName}"
        }.ifBlank { "No bydauto methods visible on ${device!!.javaClass.name}" }
        return buildString {
            appendLine("== methods ==")
            appendLine(methodText)
            appendLine("== constants ==")
            append(dumpConstFields(device!!.javaClass))
        }
    }

    private fun ensureDevice(): Boolean = isBound()

    private fun notBound() = CommandResult(
        success = false,
        method = "(unbound)",
        raw = null,
        detail = lastBindError ?: "AC device not bound"
    )

    private fun firstSuccess(vararg attempts: () -> CommandResult): CommandResult {
        var last: CommandResult? = null
        for (attempt in attempts) {
            val result = attempt()
            if (result.success) return result
            if (result.detail != "no such method") last = result
        }
        return last ?: attempts.first().invoke()
    }

    private fun call(name: String, vararg args: Any): CommandResult = callOn(device, name, *args)

    private fun callOn(dev: Any?, name: String, vararg args: Any): CommandResult {
        if (dev == null) return notBound()
        val types = args.map { argType(it) }.toTypedArray()
        return try {
            val method = dev.javaClass.getMethod(name, *types)
            val boxed = method.invoke(dev, *args)
            val raw = (boxed as? Number)?.toInt()
            val ok = raw == null || isSuccessCode(raw)
            CommandResult(
                success = ok,
                method = formatCall(name, args),
                raw = raw,
                detail = describeCode(raw)
            )
        } catch (e: NoSuchMethodException) {
            CommandResult(false, formatCall(name, args), null, "no such method")
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            CommandResult(false, formatCall(name, args), null, "${c.javaClass.simpleName}: ${c.message}")
        }
    }

    private fun ensureBodywork(): Any? {
        bodywork?.let { return it }
        return try {
            val cls = Class.forName(BODYWORK_CLASS)
            val method = cls.getMethod("getInstance", Context::class.java)
            val inst = invokeGetInstance(method, permContext) ?: invokeGetInstance(method, appContext)
            bodywork = inst
            inst
        } catch (_: Throwable) {
            null
        }
    }

    private fun callBodyworkRearDefrost(state: Int): CommandResult {
        val dev = ensureBodywork() ?: return CommandResult(false, "bodywork", null, "no such method")
        var last = CommandResult(false, "bodywork", null, "no such method")
        for (name in REAR_HEAT_METHODS) {
            val result = firstSuccess(
                { callOn(dev, name, SOURCE_VOICE, state) },
                { callOn(dev, name, state, SOURCE_VOICE) },
                { callOn(dev, name, SOURCE_VOICE, rearDefrostArea(), state) },
                { callOn(dev, name, rearDefrostArea(), state, SOURCE_VOICE) },
                { callOn(dev, name, state) },
            )
            if (result.success) return result
            if (result.detail != "no such method") last = result
        }
        return last
    }

    private fun pauseForEcu() {
        runCatching { Thread.sleep(80) }
    }

    private fun dumpConstFields(start: Class<*>): String {
        val out = mutableListOf<String>()
        var cls: Class<*>? = start
        while (cls != null && (cls.name.contains("bydauto") || cls == start)) {
            if (!cls.name.contains("bydauto")) {
                cls = cls.superclass
                continue
            }
            for (field in cls.declaredFields) {
                val n = field.name
                if (!n.contains("CYCLE", true) && !n.contains("DEFROST", true) &&
                    !n.contains("WIND", true) && !n.contains("LOOP", true) &&
                    !n.contains("AREA", true) && !n.contains("MIRROR", true) &&
                    !n.contains("HEAT", true)
                ) {
                    continue
                }
                field.isAccessible = true
                val value = runCatching { field.get(null) }.getOrNull() ?: continue
                out += "${cls.simpleName}.$n = $value"
            }
            cls = cls.superclass
        }
        return out.sorted().joinToString("\n").ifBlank { "(no CYCLE/DEFROST/WIND constants visible)" }
    }

    private fun getInt(name: String): Int? {
        val dev = device ?: return null
        return runCatching {
            (dev.javaClass.getMethod(name).invoke(dev) as? Number)?.toInt()
        }.getOrNull()?.takeUnless { it in SENTINELS }
    }

    private fun getIntArg(name: String, arg: Int): Int? {
        val dev = device ?: return null
        return runCatching {
            (dev.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(dev, arg) as? Number)?.toInt()
        }.getOrNull()?.takeUnless { it in SENTINELS }
    }

    private fun defrostState(area: Int): Int? =
        getIntArg("getAcDefrostState", area) ?: getIntArg("getDefrostState", area)

    private fun frontDefrostArea(): Int = constInt(
        "AC_DEFROST_AREA_FRONT",
        "AC_COMMAND_DEFROST_FRONT",
        "DEFROST_FRONT",
        "AC_DEFROST_FRONT",
    ) ?: 1

    private fun rearDefrostArea(): Int {
        val rear = constInt(
            "AC_DEFROST_AREA_REAR",
            "AC_COMMAND_DEFROST_REAR",
            "DEFROST_REAR",
            "AC_DEFROST_REAR",
        ) ?: 2
        val front = constInt(
            "AC_DEFROST_AREA_FRONT",
            "AC_COMMAND_DEFROST_FRONT",
            "DEFROST_FRONT",
            "AC_DEFROST_FRONT",
        ) ?: 1
        return if (rear == front) 2 else rear
    }

    private fun cycleValue(recirc: Boolean): Int {
        val name = if (recirc) {
            arrayOf("AC_CYCLEMODE_INLOOP", "AC_CYCLE_IN", "CYCLE_IN", "AC_INLOOP")
        } else {
            arrayOf("AC_CYCLEMODE_OUTLOOP", "AC_CYCLE_OUT", "CYCLE_OUT", "AC_OUTLOOP")
        }
        return constInt(*name) ?: if (recirc) CYCLE_RECIRC else CYCLE_FRESH
    }

    private fun constInt(vararg names: String): Int? {
        var cls: Class<*>? = device?.javaClass ?: return null
        while (cls != null) {
            for (name in names) {
                runCatching { cls!!.getField(name).getInt(null) }.getOrNull()?.let { return it }
                runCatching {
                    cls!!.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
                }.getOrNull()?.let { return it }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun setViaDeviceManager(featureId: Int, value: Int): CommandResult {
        val dev = device ?: return notBound()
        return try {
            var cls: Class<*>? = dev.javaClass
            var field: java.lang.reflect.Field? = null
            while (cls != null && field == null) {
                field = runCatching { cls!!.getDeclaredField("mDeviceManager") }.getOrNull()
                cls = cls.superclass
            }
            if (field == null) {
                return CommandResult(false, "mDeviceManager.setInt", null, "no such field")
            }
            field.isAccessible = true
            val manager = field.get(dev)
                ?: return CommandResult(false, "mDeviceManager.setInt", null, "null manager")
            val method = manager.javaClass.methods.firstOrNull { m ->
                m.name == "setInt" && m.parameterCount == 3
            } ?: return CommandResult(false, "mDeviceManager.setInt", null, "no such method")
            val raw = (method.invoke(manager, DEVICE_TYPE_AC, featureId, value) as? Number)?.toInt()
            val ok = raw == null || isSuccessCode(raw)
            CommandResult(
                success = ok,
                method = "mDeviceManager.setInt($DEVICE_TYPE_AC, 0x${featureId.toString(16)}, $value)",
                raw = raw,
                detail = describeCode(raw)
            )
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            CommandResult(
                false,
                "mDeviceManager.setInt",
                null,
                "${c.javaClass.simpleName}: ${c.message}"
            )
        }
    }

    companion object {
        private const val TAG = "BydAcController"
        private const val AC_CLASS = "android.hardware.bydauto.ac.BYDAutoAcDevice"
        private const val BODYWORK_CLASS = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"
        private const val ZONE_DRIVER = 1
        private const val ZONE_PASSENGER = 2
        private const val ZONE_OUTSIDE = 4
        private const val AC_POWER_OFF = 0
        private const val AC_POWER_ON = 1
        private const val SOURCE_UI = 0
        private const val SOURCE_VOICE = 1
        private const val CONTROL_AUTO = 0
        private const val CONTROL_MANUAL = 1
        /** Fallback when SDK constants are missing: 0 = linked, 1 = dual-zone. */
        private const val TEMP_CTRL_SYNCED = 0
        private const val TEMP_CTRL_SEPARATE = 1
        // Getter on DiLink 5: 1 = recirc, 0 = fresh. Named 2-arg SET is (source, mode).
        private const val CYCLE_RECIRC = 1
        private const val CYCLE_FRESH = 0
        private const val WIND_DEFROST = 0
        private val REAR_HEAT_METHODS = arrayOf(
            "setAcRearDefrostState",
            "setRearDefrostState",
            "setDefrostRearState",
            "setDefrostRear",
            "setAcDefrostRearState",
            "setRearWindowDefrostState",
            "setBackDefrostState",
        )
        private const val DEVICE_TYPE_AC = 1000
        private const val FEATURE_WIND_LEVEL = 0x1DE0000C
        const val TEMP_MIN = 17
        const val TEMP_MAX = 32
        private const val FAN_MIN = 1
        private const val FAN_MAX = 7

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

        private fun formatCall(name: String, args: Array<out Any>): String =
            "$name(${args.joinToString(", ")})"

        private fun isSuccessCode(raw: Int): Boolean = raw >= 0

        private fun describeCode(raw: Int?): String = when (raw) {
            null -> "invoked (void/unknown)"
            0 -> "success"
            -2147482648 -> "failed"
            -2147482647 -> "busy"
            -2147482646 -> "timeout"
            -2147482645 -> "invalid value"
            else -> "code=$raw"
        }

        private fun intToBool(v: Int?): Boolean? = when (v) {
            null -> null
            0 -> false
            else -> true
        }

        private fun plausibleTemp(v: Int?): Int? = v?.takeIf { it in -40..50 }

        private fun fmtTemp(v: Int?): String = if (v == null) "—" else "$v°C"

        private fun fmtOnOff(v: Boolean?): String = when (v) {
            true -> "ON"
            false -> "OFF"
            null -> "—"
        }

        private fun fmtControl(v: Int?): String = when (v) {
            0 -> "Auto"
            1 -> "Manual"
            null -> "—"
            else -> v.toString()
        }

        private fun fmtCycle(v: Int?): String = when (v) {
            1 -> "Recirc"
            0, 2 -> "Fresh (flow-through)"
            null -> "—"
            else -> v.toString()
        }
    }
}
