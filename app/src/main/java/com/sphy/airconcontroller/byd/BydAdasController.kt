package com.sphy.airconcontroller.byd

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * ADAS toggles that Shark typically resets after power cycle.
 *
 * On-car notes (Shark):
 * - LDA get is 0/1/2/3 (Off/Warning/Prevent/Both).
 *   Set via DiPilot LaneAssistType: 1/2/4 work; Prevent uses DEPARTURE_STOP(5) not KEEP(3).
 *   Feature LKS (3255/3256) uses the same 0/1/2/3 encoding as get.
 * - ELKA get is 0/1 style; set ON=1 OFF=0 (SET_ON=2 does not stick).
 * - AEB via AiEmergencyBrake works.
 * - Cabin camera / DMC: try DMS device (setDmsSwtichStatus), CPD/IMS, then DiPilot aids.
 */
class BydAdasController(context: Context) {
    private val appContext = context.applicationContext
    private val permContext = BydPermissionContext(appContext)

    @Volatile private var diPilot: Any? = null
    @Volatile private var dmsDevice: Any? = null

    /** Last DMS switch value from async callback / fresh query (null until known). */
    @Volatile private var lastDmsSwitch: Int? = null

    /** Bumped whenever [lastDmsSwitch] is updated from HAL/callback — detects stale cache. */
    @Volatile private var dmsSwitchSeq: Int = 0

    @Volatile
    var lastBindError: String? = null
        private set

    enum class LaneDepartureMode(val readRaw: Int, val writeRaw: Int) {
        OFF(0, 1),
        WARNING(1, 2),
        // Shark rejects KEEP(3); DEPARTURE_STOP(5) is the Prevent/LDP write value.
        PREVENT(2, 5),
        BOTH(3, 4),
        ;

        /** DiPilot setLaneAssistType candidates (feature LKS uses [readRaw] separately). */
        val writeFallbacks: List<Int>
            get() = when (this) {
                PREVENT -> listOf(5, 3)
                else -> listOf(writeRaw)
            }

        companion object {
            fun fromRaw(raw: Int?): LaneDepartureMode? =
                entries.firstOrNull { it.readRaw == raw }
        }
    }

    data class CommandResult(
        val success: Boolean,
        val method: String?,
        val detail: String,
    )

    data class Snapshot(
        val sdkInjected: Boolean,
        val diPilotBound: Boolean,
        val bindError: String?,
        val elka: Boolean?,
        val elkaRaw: Int?,
        val laneDeparture: LaneDepartureMode?,
        val laneDepartureRaw: Int?,
        val aeb: Boolean?,
        val aebRaw: Int?,
        val driverMonitor: Boolean?,
        val driverMonitorRaw: Int?,
    )

    fun bind(): Boolean {
        lastBindError = null
        val injected = Dilink5SdkInjector.ensure(appContext)
        if (!injected && !Dilink5SdkInjector.isLoadable(appContext)) {
            lastBindError =
                "Could not inject OEM bydauto (hidden-API exemption missing, or OEM apps not installed)"
            Log.w(TAG, "bind: $lastBindError")
            return false
        }

        if (diPilot == null) {
            diPilot = loadInstance(DIPILOT_CLASS)
            diPilot?.let { bypassDiPilotPermissions(it) }
        }

        val ok = diPilot != null
        if (!ok && lastBindError == null) {
            lastBindError = "BYDAutoDiPilotDevice unavailable"
        }
        Log.i(TAG, "bind diPilot=${diPilot != null} carAssist=${carAssist() != null}")
        return ok
    }

    fun isBound(): Boolean = diPilot != null || bind()

    fun snapshot(): Snapshot {
        val injected = Dilink5SdkInjector.isLoadable(appContext) || Dilink5SdkInjector.ensure(appContext)
        if (!isBound()) {
            return Snapshot(
                sdkInjected = injected,
                diPilotBound = false,
                bindError = lastBindError,
                elka = null,
                elkaRaw = null,
                laneDeparture = null,
                laneDepartureRaw = null,
                aeb = null,
                aebRaw = null,
                driverMonitor = null,
                driverMonitorRaw = null,
            )
        }
        bypassDiPilotPermissions(diPilot)

        val elkaRaw = readInt("getEmergencyLaneAssistance32FState")
            ?: readInt("getEmergencyLaneAssistance2F0State")
            ?: pollFeature(FEATURE_ELKA_STATE)
        val laneRaw = readInt("getLaneAssistType")
            ?: pollFeature(FEATURE_LKS_MODE)
        val aebRaw = readInt("getAiEmergencyBrakeState")
            ?: pollFeature(FEATURE_AEB_STATE)
        val dmsRaw = readDriverMonitorRaw()

        Log.i(TAG, "snapshot ELKA=$elkaRaw LDA=$laneRaw AEB=$aebRaw DMC=$dmsRaw")

        return Snapshot(
            sdkInjected = injected,
            diPilotBound = true,
            bindError = lastBindError,
            elka = elkaRaw?.let { decodeElka(it) },
            elkaRaw = elkaRaw,
            laneDeparture = LaneDepartureMode.fromRaw(laneRaw),
            laneDepartureRaw = laneRaw,
            aeb = aebRaw?.let { decodeFuncOrSetOn(it) },
            aebRaw = aebRaw,
            driverMonitor = dmsRaw?.let { decodeDms(it) },
            driverMonitorRaw = dmsRaw,
        )
    }

    fun setEmergencyLaneKeepAssist(enabled: Boolean): CommandResult {
        if (!ensureDiPilot()) {
            return CommandResult(false, "ELKA", lastBindError ?: "unbound")
        }
        // Shark: OFF sticks with 0; ON sticks with 1 (not DiPilot SET_ON=2).
        return setSwitch(
            label = "ELKA",
            enabled = enabled,
            setMethods = listOf("setEmergencyLaneAssistanceState"),
            getMethods = listOf(
                "getEmergencyLaneAssistance32FState",
                "getEmergencyLaneAssistance2F0State",
            ),
            featureSetId = FEATURE_ELKA_SET,
            featureGetId = FEATURE_ELKA_STATE,
            decode = ::decodeElka,
            onValues = listOf(FUNC_ON, SET_ON),
            offValues = listOf(FUNC_OFF, SET_OFF),
        )
    }

    fun setLaneDepartureAssist(mode: LaneDepartureMode): CommandResult {
        if (!ensureDiPilot()) {
            return CommandResult(false, "setLaneAssistType", lastBindError ?: "unbound")
        }
        bypassDiPilotPermissions(diPilot)
        val attempts = mutableListOf<String>()
        val matched: (Int) -> Boolean = { LaneDepartureMode.fromRaw(it) == mode }

        for (value in mode.writeFallbacks.distinct()) {
            val viaAssist = invokeCarAssist("setLaneAssistType", value)
            if (viaAssist != null) {
                val verified = verifyAfterWrite(value, listOf("getLaneAssistType"), matched = matched)
                if (verified.success) {
                    return CommandResult(true, "CarAssist.setLaneAssistType", "write=$value ${verified.detail}")
                }
                if (viaAssist.success) {
                    return CommandResult(
                        true,
                        "CarAssist.setLaneAssistType",
                        "write=$value accepted (${verified.detail})",
                    )
                }
                attempts += "CarAssist(write=$value): ${viaAssist.detail}; verify ${verified.detail}"
            }

            val named = setAndVerifyNamed("setLaneAssistType", "getLaneAssistType", value, matched)
            if (named.success) return named.copy(detail = "write=$value ${named.detail}")
            attempts += "named(write=$value): ${named.detail}"
        }

        // Feature 3255/3256 is LKS_MODE 0/1/2/3 (same as getLaneAssistType on Shark).
        val feat = setFeatureAndVerify(FEATURE_LKS_MODE_SET, FEATURE_LKS_MODE, mode.readRaw, matched)
        if (feat.success) return feat.copy(detail = "lksWrite=${mode.readRaw} ${feat.detail}")
        attempts += "feat(lks=${mode.readRaw}): ${feat.detail}"

        return CommandResult(false, "LDA", attempts.joinToString(" | "))
    }

    fun setAutomaticEmergencyBraking(enabled: Boolean): CommandResult {
        if (!ensureDiPilot()) {
            return CommandResult(false, "AEB", lastBindError ?: "unbound")
        }
        return setSwitch(
            label = "AEB",
            enabled = enabled,
            setMethods = listOf("setAiEmergencyBrakeState"),
            getMethods = listOf("getAiEmergencyBrakeState"),
            featureSetId = FEATURE_AEB_SET,
            featureGetId = FEATURE_AEB_STATE,
            decode = ::decodeFuncOrSetOn,
        )
    }

    fun setDriverMonitoringCamera(enabled: Boolean): CommandResult {
        if (!ensureDiPilot()) {
            return CommandResult(false, "DMC", lastBindError ?: "unbound")
        }
        val attempts = mutableListOf<String>()

        // 1) OEM DMS HAL (Cabin perception driver-detection uses this path).
        val dmsAttempt = setViaDmsDevice(enabled)
        if (dmsAttempt.success) return dmsAttempt
        attempts += dmsAttempt.detail

        // 2) DiPilot / CarAssist: CPD+IMS+fatigue (Cabin perception page).
        val diPilotAttempt = setSwitch(
            label = "DMC",
            enabled = enabled,
            setMethods = listOf(
                "setDmsSwitchStatus",
                "setDmsSwtichStatus",
                "setCpdImsSwitchState",
                "setCpdStatus",
                "setIMSEnableState",
                "setImsEnableState",
                "setFatigueDetectionAidState",
                "setDistractionDetectionAidState",
                "setMOISFunctionSwitch",
            ),
            getMethods = listOf(
                "getDmsSwitchStatus",
                "getCpdImsSwitchState",
                "getCpdStatus",
                "getIMSEnableState",
                "getImsEnableState",
                "getFatigueDetectionAidState",
                "getDistractionDetectionAidState",
                "getMOISEnable",
            ),
            featureSetId = FEATURE_DRIVER_FATIGUE_SET,
            featureGetId = FEATURE_DMS_SWITCH,
            decode = ::decodeDms,
            onValues = listOf(FUNC_ON, SET_ON),
            offValues = listOf(FUNC_OFF, SET_OFF),
        )
        if (diPilotAttempt.success) return diPilotAttempt
        attempts += diPilotAttempt.detail

        // 3) Direct feature write for DMS switch feedback.
        val featValue = if (enabled) FUNC_ON else FUNC_OFF
        val feat = setFeatureAndVerify(FEATURE_DMS_SWITCH, FEATURE_DMS_SWITCH, featValue) {
            decodeDms(it) == enabled
        }
        if (feat.success) return feat
        attempts += feat.detail

        return CommandResult(false, "DMC", attempts.joinToString(" | "))
    }

    /**
     * On/off writes: try CarAssist first, then named setter, then feature-ID set.
     * Default candidates: DiPilot SET_ON/OFF (2/1) then FUNCATION ON/OFF (1/0).
     *
     * Shark often applies the write before getters catch up — if the setter returns OK we
     * treat that as success after a short verify, instead of burning seconds on retries.
     */
    private fun setSwitch(
        label: String,
        enabled: Boolean,
        setMethods: List<String>,
        getMethods: List<String>,
        featureSetId: Int?,
        featureGetId: Int?,
        decode: (Int) -> Boolean,
        onValues: List<Int> = listOf(SET_ON, FUNC_ON),
        offValues: List<Int> = listOf(SET_OFF, FUNC_OFF),
    ): CommandResult {
        bypassDiPilotPermissions(diPilot)
        val candidates = if (enabled) onValues else offValues
        val attempts = mutableListOf<String>()

        for (value in candidates) {
            val valueMatched: (Int) -> Boolean = { it == value || decode(it) == enabled }
            for (setMethod in setMethods) {
                if (!hasMethod(diPilot, setMethod) && carAssist()?.let { hasMethod(it, setMethod) } != true) {
                    continue
                }
                val viaAssist = invokeCarAssist(setMethod, value)
                if (viaAssist != null) {
                    val verified = verifyAfterWrite(value, getMethods, featureGetId, valueMatched)
                    if (verified.success) {
                        return CommandResult(true, "CarAssist.$setMethod", "value=$value ${verified.detail}")
                    }
                    if (viaAssist.success) {
                        // Setter accepted; getter lag — don't keep trying other encodings.
                        return CommandResult(
                            true,
                            "CarAssist.$setMethod",
                            "value=$value accepted (${verified.detail})",
                        )
                    }
                    attempts += "CarAssist.$setMethod($value): ${verified.detail}"
                }

                val readableGets = getMethods.filter { hasMethod(diPilot, it) || carAssist()?.let { a -> hasMethod(a, it) } == true }
                if (hasMethod(diPilot, setMethod) && readableGets.isNotEmpty()) {
                    val getMethod = readableGets.first()
                    val named = setAndVerifyNamed(setMethod, getMethod, value, valueMatched)
                    if (named.success) return named.copy(detail = "value=$value ${named.detail}")
                    attempts += "$setMethod($value): ${named.detail}"
                }
            }

            if (featureSetId != null) {
                val feat = setFeatureAndVerify(featureSetId, featureGetId, value, valueMatched)
                if (feat.success) return feat.copy(detail = "value=$value ${feat.detail}")
                attempts += "feat$featureSetId($value): ${feat.detail}"
            }
        }

        return CommandResult(false, label, attempts.joinToString(" | ").ifEmpty { "no API" })
    }

    /** ELKA on Shark: get uses 0=off, 1 (or 2)=on. */
    private fun decodeElka(raw: Int): Boolean = raw == FUNC_ON || raw == SET_ON

    /** FUNCATION_* or mixed: on=1 or 2, off=0. */
    private fun decodeFuncOrSetOn(raw: Int): Boolean = raw == SET_ON || raw == FUNC_ON

    /** DMS/CPD/IMS: treat 1 or 2 as on. */
    private fun decodeDms(raw: Int): Boolean = raw == FUNC_ON || raw == SET_ON

    /**
     * Fresh DMS read. The car Settings toggle is async over DmsSettingsManager — a cached
     * [lastDmsSwitch] goes stale when the user flips Cabin perception outside our process.
     */
    private fun readDriverMonitorRaw(): Int? {
        ensureDmsDevice()
        // Kick async refresh but don't block — sync getter / cache is enough for UI.
        requestDmsSwitchRefresh()

        readDmsSettingsInt("getDmsSwitchStatus")?.let {
            lastDmsSwitch = it
            return it
        }
        lastDmsSwitch?.let { return it }

        pollFeature(FEATURE_DMS_SWITCH)?.let { return it }

        val getters = listOf(
            "getDmsSwitchStatus",
            "getIMSEnableState",
            "getImsEnableState",
            "getCpdImsSwitchState",
            "getCpdStatus",
            "getFatigueDetectionAidState",
            "getDistractionDetectionAidState",
            "getMOISEnable",
        )
        return getters.firstNotNullOfOrNull { readInt(it) }
            ?: pollFeature(FEATURE_FATIGUE_MONITOR_STATE)
    }

    /** Fire-and-forget DMS query (no wait). */
    private fun requestDmsSwitchRefresh() {
        try {
            val mgr = dmsSettingsManager()
            if (mgr != null && hasMethod(mgr, "getDmsSwitchChanged")) {
                mgr.javaClass.getMethod("getDmsSwitchChanged").invoke(mgr)
                return
            }
            val device = dmsDevice
            if (device != null && hasMethod(device, "getDmsSwitchChanged")) {
                device.javaClass.getMethod("getDmsSwitchChanged").invoke(device)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "requestDmsSwitchRefresh: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Ask HAL for current DMS switch; optionally wait briefly for callback/sync read. */
    private fun refreshDmsSwitchFromHal(waitMs: Long = 0L) {
        val beforeSeq = dmsSwitchSeq
        requestDmsSwitchRefresh()
        readDmsSettingsInt("getDmsSwitchStatus")?.let {
            if (lastDmsSwitch != it) {
                lastDmsSwitch = it
                dmsSwitchSeq++
            }
            return
        }
        if (waitMs <= 0L) return
        val deadline = System.nanoTime() + waitMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (dmsSwitchSeq != beforeSeq) return
            readDmsSettingsInt("getDmsSwitchStatus")?.let {
                lastDmsSwitch = it
                dmsSwitchSeq++
                return
            }
            try {
                Thread.sleep(40L)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun dmsSettingsManager(): Any? {
        return try {
            val smCls = Class.forName("android.hardware.bydauto.SettingsManagerImpl")
            val sm = smCls.getMethod("getInstance", Context::class.java)
                .invoke(null, permContext)
                ?: smCls.getMethod("getInstance", Context::class.java).invoke(null, appContext)
            smCls.getMethod("getDmsSettingsManager").invoke(sm)
        } catch (t: Throwable) {
            Log.w(TAG, "dmsSettingsManager: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun readDmsSettingsInt(method: String): Int? {
        val mgr = dmsSettingsManager() ?: return null
        if (!hasMethod(mgr, method)) return null
        return try {
            (mgr.javaClass.getMethod(method).invoke(mgr) as? Number)?.toInt()
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            Log.w(TAG, "DmsSettings.$method: ${c.javaClass.simpleName}: ${c.message}")
            null
        }
    }

    private fun ensureDmsDevice(): Any? {
        if (dmsDevice != null) return dmsDevice
        dmsDevice = loadInstance(DMS_CLASS)
        registerDmsListeners()
        return dmsDevice
    }

    private fun registerDmsListeners() {
        // OnDmsChangedListener is an interface — Proxy works. IDMSListener is often an abstract class.
        try {
            val listenerCls = Class.forName("com.ts.lib.settings.dms.OnDmsChangedListener")
            if (listenerCls.isInterface) {
                val holder = arrayOfNulls<Any>(1)
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    listenerCls.classLoader,
                    arrayOf(listenerCls),
                ) { _, method, args ->
                    when (method.name) {
                        "onDmsSwitchChanged" -> {
                            val v = (args?.getOrNull(0) as? Number)?.toInt()
                            if (v != null) {
                                lastDmsSwitch = v
                                dmsSwitchSeq++
                                Log.i(TAG, "onDmsSwitchChanged=$v")
                            }
                        }
                        "equals" -> args?.getOrNull(0) === holder[0]
                        "hashCode" -> System.identityHashCode(holder[0] ?: this)
                        "toString" -> "OnDmsChangedListenerProxy"
                    }
                    null
                }
                holder[0] = proxy
                val smCls = Class.forName("android.hardware.bydauto.SettingsManagerImpl")
                val sm = smCls.getMethod("getInstance", Context::class.java).invoke(null, permContext)
                smCls.getMethod("setDmsChangedListenerListener", listenerCls).invoke(sm, proxy)
                Log.i(TAG, "registered OnDmsChangedListener")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "register OnDmsChangedListener: ${t.javaClass.simpleName}: ${t.message}")
        }

        val device = dmsDevice ?: return
        try {
            val listenerCls = Class.forName("android.hardware.bydauto.dms.IDMSListener")
            if (!listenerCls.isInterface) {
                Log.i(TAG, "IDMSListener is not an interface; skipping Proxy")
                return
            }
            val holder = arrayOfNulls<Any>(1)
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerCls.classLoader,
                arrayOf(listenerCls),
            ) { _, method, args ->
                when (method.name) {
                    "onDmsSwitchChanged" -> {
                        val v = (args?.getOrNull(0) as? Number)?.toInt()
                        if (v != null) {
                            lastDmsSwitch = v
                            dmsSwitchSeq++
                            Log.i(TAG, "IDMSListener.onDmsSwitchChanged=$v")
                        }
                    }
                    "equals" -> args?.getOrNull(0) === holder[0]
                    "hashCode" -> System.identityHashCode(holder[0] ?: this)
                    "toString" -> "IDMSListenerProxy"
                }
                null
            }
            holder[0] = proxy
            device.javaClass.getMethod("registerListener", listenerCls).invoke(device, proxy)
        } catch (t: Throwable) {
            Log.w(TAG, "register IDMSListener: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun setViaDmsDevice(enabled: Boolean): CommandResult {
        val device = ensureDmsDevice()
            ?: return CommandResult(false, "BYDAutoDmsDevice", "unavailable")
        val value = if (enabled) DMS_ON else DMS_OFF
        val attempts = mutableListOf<String>()

        // Write through DmsSettingsManager first (same binder Settings uses).
        val mgr = dmsSettingsManager()
        if (mgr != null) {
            for (method in listOf("setDmsSwtichStatus", "setDmsSwitchStatus")) {
                if (!hasMethod(mgr, method)) continue
                try {
                    mgr.javaClass.getMethod(method, Int::class.javaPrimitiveType).invoke(mgr, value)
                    lastDmsSwitch = value
                    dmsSwitchSeq++
                    requestDmsSwitchRefresh()
                    val verified = verifyDmsSwitch(value, enabled)
                    // Void setters: no result code — accept after short verify or optimistically.
                    return CommandResult(
                        true,
                        "DmsSettings.$method",
                        if (verified.success) verified.detail else "value=$value accepted (${verified.detail})",
                    )
                } catch (t: Throwable) {
                    val c = (t as? InvocationTargetException)?.cause ?: t
                    attempts += "DmsSettings.$method: ${c.javaClass.simpleName}: ${c.message}"
                }
            }
        }

        for (method in listOf("setDmsSwtichStatus", "setDmsSwitchStatus")) {
            if (!hasMethod(device, method)) continue
            try {
                device.javaClass.getMethod(method, Int::class.javaPrimitiveType).invoke(device, value)
                lastDmsSwitch = value
                dmsSwitchSeq++
                requestDmsSwitchRefresh()
                val verified = verifyDmsSwitch(value, enabled)
                return CommandResult(
                    true,
                    "Dms.$method",
                    if (verified.success) verified.detail else "value=$value accepted (${verified.detail})",
                )
            } catch (t: Throwable) {
                val c = (t as? InvocationTargetException)?.cause ?: t
                attempts += "$method: ${c.javaClass.simpleName}: ${c.message}"
            }
        }

        return CommandResult(false, "DmsDevice", attempts.joinToString(" | ").ifEmpty { "no API" })
    }

    private fun verifyDmsSwitch(wrote: Int, enabled: Boolean): CommandResult {
        var after: Int? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) {
                try {
                    Thread.sleep(60L)
                } catch (_: InterruptedException) {
                }
            }
            refreshDmsSwitchFromHal(waitMs = if (attempt == 0) 0L else 80L)
            after = readDmsSettingsInt("getDmsSwitchStatus")
                ?: lastDmsSwitch
                ?: pollFeature(FEATURE_DMS_SWITCH)
            if (after != null && (after == wrote || decodeDms(after) == enabled)) {
                return CommandResult(true, null, "wrote=$wrote after=$after")
            }
        }
        return CommandResult(false, null, "HAL lag: wrote=$wrote after=$after lastDms=$lastDmsSwitch")
    }

    private fun tsManagerMethod(name: String): Any? {
        val device = diPilot ?: return null
        return try {
            val manager = fieldValue(device, "mDiPilotManagerImpl") ?: return null
            val ts = fieldValue(manager, "mTsManagerImpl") ?: return null
            ts.javaClass.methods
                .firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.invoke(ts)
        } catch (t: Throwable) {
            Log.w(TAG, "tsManagerMethod($name): ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun ensureDiPilot(): Boolean {
        if (diPilot != null) return true
        bind()
        return diPilot != null
    }

    private fun bypassDiPilotPermissions(device: Any?) {
        if (device == null) return
        try {
            val manager = fieldValue(device, "mDiPilotManagerImpl")
                ?: fieldValue(device) { it.contains("DiPilotManager", ignoreCase = true) }
                ?: return

            runCatching {
                val f = manager.javaClass.getDeclaredField("mIgnoreBydPermission")
                f.isAccessible = true
                f.setBoolean(manager, true)
            }
            runCatching {
                val f = manager.javaClass.getDeclaredField("mContext")
                f.isAccessible = true
                f.set(manager, permContext)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bypassDiPilotPermissions: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Live CarAssist binder — same path Settings uses after permission checks. */
    private fun carAssist(): Any? {
        val device = diPilot ?: return null
        return try {
            val manager = fieldValue(device, "mDiPilotManagerImpl") ?: return null
            val ts = fieldValue(manager, "mTsManagerImpl") ?: return null
            ts.javaClass.methods
                .firstOrNull { it.name == "getCarAssistAdapterManager" && it.parameterCount == 0 }
                ?.invoke(ts)
        } catch (t: Throwable) {
            Log.w(TAG, "carAssist: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun invokeCarAssist(method: String, value: Int): CommandResult? {
        val assist = carAssist() ?: return null
        if (!hasMethod(assist, method)) return null
        return try {
            val ret = assist.javaClass
                .getMethod(method, Int::class.javaPrimitiveType)
                .invoke(assist, value)
            val code = (ret as? Number)?.toInt()
            Log.i(TAG, "CarAssist.$method($value) -> $code")
            CommandResult(isSetSuccess(code, value), "CarAssist.$method", "result=$code")
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            CommandResult(false, "CarAssist.$method", "${c.javaClass.simpleName}: ${c.message}")
        }
    }

    private fun readInt(name: String): Int? {
        // Prefer CarAssist (permission-free on client).
        carAssist()?.let { assist ->
            if (hasMethod(assist, name)) {
                runCatching {
                    (assist.javaClass.getMethod(name).invoke(assist) as? Number)?.toInt()
                }.getOrNull()?.let { return it }
            }
        }
        return getInt(diPilot, name)
    }

    private fun verifyAfterWrite(
        wrote: Int,
        getMethods: List<String>,
        featureGetId: Int? = null,
        matched: (Int) -> Boolean,
    ): CommandResult {
        var after: Int? = null
        // Immediate read, then two short retries — Shark getters often lag the real apply.
        for (attempt in 0 until 3) {
            if (attempt > 0) {
                try {
                    Thread.sleep(70L)
                } catch (_: InterruptedException) {
                }
            }
            after = getMethods.firstNotNullOfOrNull { readInt(it) }
                ?: featureGetId?.let { pollFeature(it) }
            if (after != null && matched(after)) {
                return CommandResult(true, null, "wrote=$wrote after=$after")
            }
        }
        return CommandResult(false, null, "HAL lag: wrote=$wrote after=$after")
    }

    private fun setAndVerifyNamed(
        setMethod: String,
        getMethod: String,
        value: Int,
        matched: (Int) -> Boolean,
    ): CommandResult {
        val before = readInt(getMethod)
        val setResult = setInt(diPilot, setMethod, value)
        if (!setResult.success) return setResult
        val verified = verifyAfterWrite(value, listOf(getMethod), matched = matched)
        return if (verified.success) {
            CommandResult(true, setMethod, "before=$before ${verified.detail}")
        } else {
            // Setter returned OK; accept despite getter lag.
            CommandResult(true, setMethod, "before=$before accepted (${verified.detail})")
        }
    }

    private fun setFeatureAndVerify(
        featureSetId: Int,
        featureGetId: Int?,
        value: Int,
        matched: (Int) -> Boolean,
    ): CommandResult {
        val before = featureGetId?.let { pollFeature(it) }
        val setResult = setFeature(featureSetId, value)
        if (!setResult.success) return setResult
        val verified = verifyAfterWrite(value, emptyList(), featureGetId, matched)
        return if (verified.success) {
            CommandResult(true, "set($featureSetId)", "before=$before ${verified.detail}")
        } else {
            CommandResult(true, "set($featureSetId)", "before=$before accepted (${verified.detail})")
        }
    }

    private fun setFeature(featureId: Int, value: Int): CommandResult {
        val device = diPilot ?: return CommandResult(false, "set($featureId)", "unbound")
        return try {
            val evClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue")
            val ev = evClass.getDeclaredConstructor().newInstance()
            runCatching { evClass.getField("intValue").setInt(ev, value) }
            val ret = device.javaClass
                .getMethod("set", IntArray::class.java, evClass)
                .invoke(device, intArrayOf(featureId), ev)
            val code = (ret as? Number)?.toInt()
            CommandResult(isSetSuccess(code, value), "set($featureId)", "result=$code")
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            CommandResult(false, "set($featureId)", "${c.javaClass.simpleName}: ${c.message}")
        }
    }

    private fun pollFeature(featureId: Int): Int? {
        val device = diPilot ?: return null
        return try {
            val evClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue")
            val result = device.javaClass
                .getMethod("get", IntArray::class.java, Class::class.java)
                .invoke(device, intArrayOf(featureId), evClass) ?: return null
            (result.javaClass.getField("intValue").get(result) as? Number)?.toInt()
        } catch (t: Throwable) {
            null
        }
    }

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

    private fun fieldValue(obj: Any, name: String): Any? =
        runCatching {
            var cls: Class<*>? = obj.javaClass
            while (cls != null) {
                val f = runCatching { cls!!.getDeclaredField(name) }.getOrNull()
                if (f != null) {
                    f.isAccessible = true
                    return f.get(obj)
                }
                cls = cls.superclass
            }
            null
        }.getOrNull()

    private fun fieldValue(obj: Any, pred: (String) -> Boolean): Any? =
        runCatching {
            var cls: Class<*>? = obj.javaClass
            while (cls != null) {
                val f = cls.declaredFields.firstOrNull { pred(it.name) }
                if (f != null) {
                    f.isAccessible = true
                    return f.get(obj)
                }
                cls = cls.superclass
            }
            null
        }.getOrNull()

    private fun hasMethod(device: Any?, name: String): Boolean =
        device != null && device.javaClass.methods.any { it.name == name }

    private fun getInt(device: Any?, name: String): Int? {
        if (device == null || !hasMethod(device, name)) return null
        return try {
            (device.javaClass.getMethod(name).invoke(device) as? Number)?.toInt()
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            Log.w(TAG, "$name(): ${c.javaClass.simpleName}: ${c.message}")
            null
        }
    }

    private fun setInt(device: Any?, name: String, value: Int): CommandResult {
        if (device == null) return CommandResult(false, name, "device unbound")
        if (!hasMethod(device, name)) return CommandResult(false, name, "NoSuchMethod")
        return try {
            val ret = device.javaClass
                .getMethod(name, Int::class.javaPrimitiveType)
                .invoke(device, value)
            val code = (ret as? Number)?.toInt()
            CommandResult(isSetSuccess(code, value), name, "result=$code")
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            CommandResult(false, name, "${c.javaClass.simpleName}: ${c.message}")
        }
    }

    private fun isSetSuccess(code: Int?, value: Int): Boolean {
        if (code == null || code == COMMAND_SUCCESS) return true
        if (code == value) return true
        if (code in COMMAND_ERRORS) return false
        // Adapter "still connecting" often returns 0 — treat as soft ok; verify catches no-ops.
        return code in 0..16
    }

    companion object {
        private const val TAG = "BydAdasController"
        private const val DIPILOT_CLASS = "android.hardware.bydauto.dipilot.BYDAutoDiPilotDevice"
        private const val DMS_CLASS = "android.hardware.bydauto.dms.BYDAutoDmsDevice"

        private const val COMMAND_SUCCESS = 0
        private val COMMAND_ERRORS = setOf(
            -2147482645,
            -2147482646,
            -2147482647,
            -2147482648,
        )

        private const val SET_OFF = 1
        private const val SET_ON = 2
        private const val FUNC_OFF = 0
        private const val FUNC_ON = 1
        private const val DMS_OFF = 0
        private const val DMS_ON = 1

        /** BYDAutoFeatureIds */
        private const val FEATURE_DMS_SWITCH = 632
        private const val FEATURE_DRIVER_FATIGUE_SET = 635
        private const val FEATURE_FATIGUE_MONITOR_STATE = 656
        private const val FEATURE_AEB_STATE = 3133
        private const val FEATURE_AEB_SET = 3134
        private const val FEATURE_ELKA_SET = 3181
        private const val FEATURE_ELKA_STATE = 3182
        private const val FEATURE_LKS_MODE = 3255
        private const val FEATURE_LKS_MODE_SET = 3256
    }
}
