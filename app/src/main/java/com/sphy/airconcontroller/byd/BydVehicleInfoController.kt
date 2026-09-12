package com.sphy.airconcontroller.byd

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Aggregates DiLink-5 vehicle reads for the Vehicle Info page, plus Android tablet IMU.
 *
 * Prefer named getters confirmed live on-car; FeatureId polls are kept as a secondary
 * path (often zero/null on this firmware while parked).
 */
class BydVehicleInfoController(context: Context) {
    private val appContext = context.applicationContext
    private val permContext = BydPermissionContext(appContext)
    private val tyres = BydTyreController(context)

    @Volatile private var featureIdsClass: Class<*>? = null
    @Volatile private var eventValueClass: Class<*>? = null

    private val devices = LinkedHashMap<String, Any?>()

    @Volatile
    var lastBindError: String? = null
        private set

    data class BatteryInfo(
        val bound: Boolean,
        val socPct: Double? = null,
        val remainingPowerPct: Double? = null,
        /** Usable remaining energy from [getEVRemainingBatteryPower] (kWh), when plausible. */
        val remainingUsableKwh: Double? = null,
        /**
         * Estimated pack capacity (kWh) = remainingUsable / (SOC/100).
         * [getBatteryPackEnergy] is not kWh on this car (e.g. Shark reads ~143).
         */
        val packEnergyKwh: Double? = null,
        val evRangeKm: Double? = null,
        val fuelPct: Double? = null,
        val fuelRangeKm: Double? = null,
        val combinedRangeKm: Double? = null,
        val odoKm: Double? = null,
        val evMilesKm: Double? = null,
        val hevMilesKm: Double? = null,
        val driveTimeH: Double? = null,
        val avgSpeedKmh: Double? = null,
        val lifetimeElecKwh: Double? = null,
        val lifetimeElecAvg: Double? = null,
        /** Lifetime energy from external chargers — Instrument.getExternalChargingPower. */
        val lifetimeChargedKwh: Double? = null,
        val lifetimeFuelL: Double? = null,
        val lifetimeFuelAvg: Double? = null,
        /** Battery SOH % when available (VehicleHealth / Instrument). */
        val sohPct: Double? = null,
        /** Bodywork pack capacity in Ah (not kWh). */
        val capacityAh: Double? = null,
        val tripElec: Double? = null,
        val last50KmEvAvg: Double? = null,
        val tripEvAvg: Double? = null,
        val energyMode: Double? = null,
        val operationMode: Int? = null,
    )

    data class DynamicsInfo(
        val speedKmh: Double? = null,
        val accelPedalPct: Double? = null,
        val brakePedalPct: Double? = null,
        val gearLabel: String? = null,
        val gearRaw: Int? = null,
        val parkBrake: Int? = null,
        val enginePowerKw: Double? = null,
        val engineRpm: Double? = null,
        val oilLevel: Double? = null,
        val displacementL: Double? = null,
        val engineCode: String? = null,
        val slope: Double? = null,
        val ambientLight: Double? = null,
        val steeringDeg: Double? = null,
        val powerLevel: Double? = null,
        val batt12v: Double? = null,
        val vin: String? = null,
        val outsideTempC: Double? = null,
    )

    data class ImuInfo(
        val accelName: String? = null,
        val gravityName: String? = null,
        val gyroName: String? = null,
        val rotationName: String? = null,
        val ax: Double? = null,
        val ay: Double? = null,
        val az: Double? = null,
        val pitchDeg: Double? = null,
        val rollDeg: Double? = null,
        val gyroX: Double? = null,
        val gyroY: Double? = null,
        val gyroZ: Double? = null,
        val yawRot: Double? = null,
        val pitchRot: Double? = null,
        val rollRot: Double? = null,
    )

    data class Snapshot(
        val sdkInjected: Boolean,
        val bindNote: String?,
        val battery: BatteryInfo,
        val dynamics: DynamicsInfo,
        val imu: ImuInfo,
        val tyres: BydTyreController.TyreSnapshot,
        val devicesBound: Map<String, Boolean>,
    ) {
        fun toDisplayString(): String = buildString {
            appendLine("SDK injected: $sdkInjected")
            if (!bindNote.isNullOrBlank()) appendLine("Bind note: $bindNote")
            appendLine()
            appendLine("=== Battery / energy ===")
            if (!battery.bound) {
                appendLine("Statistic: not bound")
            } else {
                appendLine(
                    "SOC: ${fmt(battery.socPct, "%")}  remaining power: ${fmt(battery.remainingPowerPct, "%")}"
                )
                appendLine(
                    "Usable left: ${fmt(battery.remainingUsableKwh, " kWh")}  est. pack: ${fmt(battery.packEnergyKwh, " kWh")}"
                )
                appendLine(
                    "SOH: ${fmt(battery.sohPct, "%")}  capacity: ${fmt(battery.capacityAh, " Ah")}"
                )
                appendLine(
                    "EV range: ${fmt(battery.evRangeKm, " km")}  fuel: ${fmt(battery.fuelPct, "%")} / ${fmt(battery.fuelRangeKm, " km")}"
                )
                appendLine("Combined range: ${fmt(battery.combinedRangeKm, " km")}")
                appendLine(
                    "Odo: ${fmt(battery.odoKm, " km")}  EV: ${fmt(battery.evMilesKm, " km")}  HEV: ${fmt(battery.hevMilesKm, " km")}"
                )
                appendLine(
                    "Drive time: ${fmt(battery.driveTimeH, " h")}  avg speed: ${fmt(battery.avgSpeedKmh, " km/h")}"
                )
                appendLine(
                    "Lifetime EV used (consumption): ${fmt(battery.lifetimeElecKwh, " kWh")}  avg ${fmt(battery.lifetimeElecAvg, " kWh/100km")}"
                )
                appendLine(
                    "Lifetime charged (external): ${fmt(battery.lifetimeChargedKwh, " kWh")}"
                )
                appendLine(
                    "Lifetime fuel: ${fmt(battery.lifetimeFuelL, " L")}  avg ${fmt(battery.lifetimeFuelAvg, " L/100km")}"
                )
                appendLine("Current trip elec: ${fmt(battery.tripElec)}")
                appendLine(
                    "Last 50km EV avg: ${fmt(battery.last50KmEvAvg)}  trip avg: ${fmt(battery.tripEvAvg)}"
                )
                appendLine(
                    "Energy mode: ${fmt(battery.energyMode)}  op mode: ${fmt(battery.operationMode)}"
                )
            }
            appendLine()
            appendLine("=== Vehicle dynamics ===")
            appendLine("Speed: ${fmt(dynamics.speedKmh, " km/h")}")
            appendLine(
                "Accel pedal: ${fmt(dynamics.accelPedalPct, "%")}  brake: ${fmt(dynamics.brakePedalPct, "%")}"
            )
            appendLine(
                "Gear: ${dynamics.gearLabel ?: "—"} (raw=${dynamics.gearRaw ?: "—"})  park brake: ${
                    when (dynamics.parkBrake) {
                        null -> "—"
                        0 -> "Off"
                        else -> "On"
                    }
                }"
            )
            appendLine(
                "Power: ${fmt(dynamics.enginePowerKw, " kW")}  RPM: ${fmt(dynamics.engineRpm)}"
            )
            appendLine(
                "Oil level: ${fmt(dynamics.oilLevel)}  displacement: ${fmt(dynamics.displacementL, " L")}"
            )
            appendLine("Engine code: ${dynamics.engineCode ?: "—"}")
            appendLine(
                "BYD slope: ${fmt(dynamics.slope)}  ambient light: ${fmt(dynamics.ambientLight)}"
            )
            appendLine("Steering angle: ${fmt(dynamics.steeringDeg, " deg")}")
            appendLine(
                "Power level: ${fmt(dynamics.powerLevel)}  12V batt: ${fmt(dynamics.batt12v)}"
            )
            appendLine("VIN: ${dynamics.vin ?: "—"}")
            appendLine()
            appendLine("=== Tablet IMU (Android) ===")
            appendLine(
                "accel: ${imu.accelName ?: "none"}  gravity: ${imu.gravityName ?: "none"}"
            )
            appendLine(
                "gyro: ${imu.gyroName ?: "none"}  rotation: ${imu.rotationName ?: "none"}"
            )
            if (imu.ax != null) {
                appendLine(
                    "aXYZ: ${fmt3(imu.ax)}, ${fmt3(imu.ay!!)}, ${fmt3(imu.az!!)} m/s2"
                )
                appendLine(
                    "est. pitch: ${fmt1(imu.pitchDeg!!)} deg  roll: ${fmt1(imu.rollDeg!!)} deg  (tablet frame)"
                )
            } else {
                appendLine("accel sample: — (no sensor or timeout)")
            }
            if (imu.gyroX != null) {
                appendLine(
                    "gyro XYZ: ${fmt3(imu.gyroX)}, ${fmt3(imu.gyroY!!)}, ${fmt3(imu.gyroZ!!)} rad/s"
                )
            } else {
                appendLine("gyro sample: —")
            }
            if (imu.yawRot != null) {
                appendLine(
                    "rotation yaw/pitch/roll: ${fmt1(imu.yawRot)} / ${fmt1(imu.pitchRot!!)} / ${fmt1(imu.rollRot!!)} deg"
                )
            } else {
                appendLine("rotation sample: —")
            }
            appendLine()
            appendLine("=== Climate (outside) ===")
            appendLine("Outside temp: ${fmt(dynamics.outsideTempC, " C")}")
            appendLine()
            appendLine("=== Tyres ===")
            if (!tyres.bound) {
                appendLine("Tyres: not bound (${tyres.bindError ?: "—"})")
            } else {
                tyres.toDisplayString()
                    .lineSequence()
                    .dropWhile { !it.startsWith("Tyres") && !it.startsWith("FL") }
                    .forEach { appendLine(it) }
            }
            appendLine()
            appendLine("=== Device bind ===")
            for ((label, ok) in devicesBound) {
                appendLine("$label: ${if (ok) "ok" else "—"}")
            }
        }.trimEnd()

        private fun fmt(v: Double?, suffix: String = ""): String =
            if (v == null) "—" else trimNum(v) + suffix

        private fun fmt(v: Int?): String = v?.toString() ?: "—"

        private fun fmt1(v: Double): String = String.format("%.1f", v)
        private fun fmt3(v: Double): String = String.format("%.3f", v)

        private fun trimNum(v: Double): String =
            if (v == v.toLong().toDouble()) v.toLong().toString()
            else String.format("%.1f", v)
    }

    fun bind(): Boolean {
        Dilink5SdkInjector.ensure(appContext)
        lastBindError = null
        devices.clear()
        for ((label, className) in DEVICE_CLASSES) {
            devices[label] = loadInstance(className)
        }
        featureIdsClass = runCatching {
            Class.forName("android.hardware.bydauto.BYDAutoFeatureIds")
        }.getOrNull()
        eventValueClass = runCatching {
            Class.forName("android.hardware.bydauto.BYDAutoEventValue")
        }.getOrNull()
        val any = devices.values.any { it != null } || tyres.bind()
        if (!any && lastBindError == null) lastBindError = "no BYD devices bound"
        return any
    }

    fun snapshot(includeImu: Boolean = true): Snapshot {
        if (devices.isEmpty()) bind()
        val injected = Dilink5SdkInjector.isLoadable(appContext)

        val battery = buildBattery()
        val dynamics = buildDynamics()
        val imu = if (includeImu) sampleAndroidImu() else ImuInfo()
        val tyreSnap = tyres.snapshot()
        val devicesBound = devices.mapValues { it.value != null }

        return Snapshot(
            sdkInjected = injected,
            bindNote = lastBindError,
            battery = battery,
            dynamics = dynamics,
            imu = imu,
            tyres = tyreSnap,
            devicesBound = devicesBound,
        )
    }

    data class DriveLive(
        val speedKmh: Double?,
        val accelPedalPct: Double?,
        val brakePedalPct: Double?,
    )

    /** Lightweight speed/pedal read for a faster UI refresh loop. */
    fun readDriveLive(): DriveLive {
        if (devices.isEmpty()) bind()
        val speed = devices["Speed"] ?: return DriveLive(null, null, null)
        return DriveLive(
            speedKmh = num(speed, "getCurrentSpeed"),
            accelPedalPct = num(speed, "getAccelerateDeepness"),
            brakePedalPct = num(speed, "getBrakeDeepness"),
        )
    }

    private fun buildBattery(): BatteryInfo {
        val stat = devices["Statistic"]
        if (stat == null) return BatteryInfo(bound = false)
        val energy = devices["Energy"]
        val soc = num(stat, "getElecPercentageValue")
        // Real usable kWh (DiLink-5 / trip-stats). getBatteryPackEnergy is a different
        // opaque scale on Shark (~143 ≠ 29.58 kWh pack) — do not treat it as kWh.
        val remainingUsable = num(stat, "getEVRemainingBatteryPower")
            ?.takeIf { it in USABLE_KWH_MIN..USABLE_KWH_MAX }
        val packEst = if (remainingUsable != null && soc != null && soc in 5.0..100.0) {
            val est = remainingUsable / (soc / 100.0)
            est.takeIf { it in PACK_KWH_MIN..PACK_KWH_MAX }
        } else {
            null
        }
        return BatteryInfo(
            bound = true,
            socPct = soc,
            remainingPowerPct = num(stat, "getRemainingBatteryPower"),
            remainingUsableKwh = remainingUsable,
            packEnergyKwh = packEst,
            evRangeKm = num(stat, "getElecDrivingRangeValue"),
            fuelPct = num(stat, "getFuelPercentageValue"),
            fuelRangeKm = num(stat, "getFuelDrivingRangeValue"),
            combinedRangeKm = num(stat, "getDrivingRangeAll"),
            odoKm = num(stat, "getTotalMileageValue"),
            evMilesKm = num(stat, "getEVMileageValue"),
            hevMilesKm = num(stat, "getHEVMileageValue"),
            driveTimeH = num(stat, "getDrivingTimeValue"),
            avgSpeedKmh = num(stat, "getAverageSpeed") ?: num(stat, "getAverageSpeedValue"),
            lifetimeElecKwh = num(stat, "getTotalElecConValue"),
            lifetimeElecAvg = num(stat, "getTotalElecConPHMValue"),
            lifetimeChargedKwh = readLifetimeChargedKwh(),
            lifetimeFuelL = num(stat, "getTotalFuelConValue"),
            lifetimeFuelAvg = num(stat, "getTotalFuelConPHMValue"),
            sohPct = readSohPct(),
            capacityAh = devices["Bodywork"]?.let { num(it, "getBatteryCapacity") }
                ?.takeIf { it in 1.0..500.0 },
            tripElec = num(stat, "getCurrentTripElec"),
            last50KmEvAvg = energy?.let {
                num(it, "getLast50KMElectricConAvg") ?: num(it, "getTripLast50KMElectricConAvg")
            },
            tripEvAvg = energy?.let {
                num(it, "getTripElecConAvg") ?: num(it, "getTripEnergyConAvg")
            },
            energyMode = energy?.let { num(it, "getEnergyMode") },
            operationMode = energy?.let { num(it, "getOperationMode")?.toInt() },
        )
    }

    /**
     * Car Settings “lifetime charging” comes from Instrument.getExternalChargingPower
     * (API 6.16 仪表类 — not Energy). Try a couple of name variants / devices.
     */
    private fun readLifetimeChargedKwh(): Double? {
        val candidates = listOf(
            "Instrument" to "getExternalChargingPower",
            "Instrument" to "getExternalChargePower",
            "Energy" to "getExternalChargingPower",
            "Energy" to "getExternalChargePower",
            "Statistic" to "getExternalChargingPower",
        )
        for ((devName, method) in candidates) {
            val dev = devices[devName] ?: continue
            val v = num(dev, method)?.takeIf { it in 0.0..100_000.0 }
            if (v != null) {
                Log.i(TAG, "lifetimeCharged via $devName.$method = $v")
                return v
            }
        }
        return null
    }

    /** SOH % — prefer VehicleHealth, then Instrument / Statistic aliases. */
    private fun readSohPct(): Double? {
        val candidates = listOf(
            "VehicleHealth" to "getBatteryHealthStatus",
            "Instrument" to "getBatteryHealthStatus",
            "Instrument" to "getSOH",
            "Instrument" to "getBatterySOH",
            "Statistic" to "getBatteryHealthyIndex",
        )
        for ((devName, method) in candidates) {
            val dev = devices[devName] ?: continue
            val v = num(dev, method)?.takeIf { it in 50.0..110.0 }
            if (v != null) {
                Log.i(TAG, "SOH via $devName.$method = $v")
                return v
            }
        }
        return null
    }

    private fun buildDynamics(): DynamicsInfo {
        val speed = devices["Speed"]
        val gearbox = devices["Gearbox"]
        val engine = devices["Engine"]
        val sensor = devices["Sensor"]
        val body = devices["Bodywork"]
        val ac = devices["AC"]

        val gear = gearbox?.let {
            num(it, "getGear")?.toInt() ?: num(it, "getGearboxAutoModeType")?.toInt()
        }
        val angleType = body?.javaClass?.let { cls ->
            constInt(cls, "BODYWORK_CMD_STEERING_WHEEL_ANGEL")
                ?: constInt(cls, "BODYWORK_CMD_STEERING_WHEEL_ANGLE")
                ?: 0
        }
        return DynamicsInfo(
            speedKmh = speed?.let { num(it, "getCurrentSpeed") },
            accelPedalPct = speed?.let { num(it, "getAccelerateDeepness") },
            brakePedalPct = speed?.let { num(it, "getBrakeDeepness") },
            gearLabel = decodeGear(gear),
            gearRaw = gear,
            parkBrake = gearbox?.let { num(it, "getParkBrakeSwitch")?.toInt() },
            enginePowerKw = engine?.let { num(it, "getEnginePower") },
            engineRpm = engine?.let { num(it, "getEngineSpeed") },
            oilLevel = engine?.let { num(it, "getOilLevel") },
            displacementL = engine?.let { num(it, "getEngineDisplacement") },
            engineCode = engine?.let { invokeString(it, "getEngineCode") },
            slope = sensor?.let { num(it, "getSlope")?.takeIf { v -> v >= 0 } },
            ambientLight = sensor?.let { num(it, "getLightIntensity") },
            steeringDeg = if (body != null && angleType != null) {
                invokeNum(body, "getSteeringWheelValue", angleType)
            } else null,
            powerLevel = body?.let { num(it, "getPowerLevel") },
            batt12v = body?.let { num(it, "getBatteryVoltageLevel") },
            vin = body?.let { invokeString(it, "getAutoVIN") ?: invokeString(it, "getVIN") },
            outsideTempC = ac?.let {
                invokeNum(it, "getTemprature", 4) ?: invokeNum(it, "getTemperature", 4)
            },
        )
    }

    fun dumpAll(): String {
        if (devices.isEmpty()) bind()
        return buildString {
            appendLine(snapshot().toDisplayString())
            appendLine()
            appendLine("=== Charge / health / HV probes ===")
            appendLine("Car Settings 外接充电电量 → Instrument.getExternalChargingPower")
            appendLine("Car Settings 总耗电量 → Statistic.getTotalElecConValue")
            appendLine("DiLink5 note: HV V/I often EVENT-only via CollectData (getters dead)")
            appendLine("DiLink5 note: pack/cell FeatureId polls often 0 while parked")
            for ((devName, method) in listOf(
                "Instrument" to "getExternalChargingPower",
                "Instrument" to "getExternalChargePower",
                "Instrument" to "getLast50KmPowerConsume",
                "Instrument" to "getAverageElectricConsumption",
                "Instrument" to "getSOH",
                "Instrument" to "getBatterySOH",
                "Instrument" to "getBatteryHealthStatus",
                "Energy" to "getExternalChargingPower",
                "Energy" to "getDischargeElecEnergy",
                "Statistic" to "getTotalElecConValue",
                "Statistic" to "getInstantElecConValue",
                "Statistic" to "getLastElecConPHMValue",
                "Statistic" to "getRemainingBatteryPower",
                "Statistic" to "getEVRemainingBatteryPower",
                "Statistic" to "getBatteryPackEnergy",
                "Bodywork" to "getBatteryCapacity",
                "Bodywork" to "getBatteryVoltageLevel",
                "VehicleHealth" to "getBatteryHealthStatus",
                "Engine" to "getMotorPower",
                "Engine" to "getEnginePower",
                "Charging" to "getChargingPower",
                "Charging" to "getChargeBatteryTemp",
                "Charging" to "getChargingCapacity",
                "AC" to "getAcSubBatteryTemperature",
                "CollectData" to "getMotorMCUGeneratrixVolt",
                "CollectData" to "getMotorMCUGeneratrixCurrent",
            )) {
                val dev = devices[devName]
                val value = if (dev == null) {
                    "device unbound"
                } else {
                    runCatching {
                        formatAny(dev.javaClass.getMethod(method).invoke(dev))
                    }.getOrElse { "err:${it.javaClass.simpleName}:${it.message}" }
                }
                appendLine("$devName.$method = $value")
            }
            appendLine()
            appendLine("=== Android sensors present ===")
            val sm = appContext.getSystemService(SensorManager::class.java)
            sm?.getSensorList(Sensor.TYPE_ALL)?.forEach { s ->
                appendLine("${s.type} ${s.stringType} name=${s.name} vendor=${s.vendor}")
            }
            appendLine()
            appendLine("=== Feature poll raw ===")
            val statDev = devices["Statistic"]
            if (statDev != null) {
                for (name in STATISTIC_FEATURES) {
                    val id = featureId(name)
                    appendLine("Statistic $name($id) = ${if (id != null) pollFeature(statDev, id) else "no id"}")
                }
            }
            val sensorDev = devices["Sensor"]
            if (sensorDev != null) {
                for (name in SENSOR_FEATURES) {
                    val id = featureId(name)
                    appendLine("Sensor $name($id) = ${if (id != null) pollFeature(sensorDev, id) else "no id"}")
                }
            }
            appendLine()
            appendLine("=== Tyre dump ===")
            appendLine(tyres.dumpMethods())
            for ((label, dev) in devices) {
                if (dev == null) continue
                appendLine()
                appendLine("=== $label methods (get*) ===")
                appendLine("class ${dev.javaClass.name}")
                dev.javaClass.methods
                    .filter { it.name.startsWith("get") && it.parameterCount <= 1 }
                    .sortedBy { it.name }
                    .forEach { m ->
                        val sample = when (m.parameterCount) {
                            0 -> runCatching { formatAny(m.invoke(dev)) }.getOrElse { "err:${it.javaClass.simpleName}" }
                            1 -> if (m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                                runCatching { formatAny(m.invoke(dev, 0)) }.getOrElse { "err" }
                            } else "—"
                            else -> "—"
                        }
                        appendLine("${m.returnType.simpleName} ${m.name}(...) = $sample")
                    }
            }
        }
    }

    /** One-shot sample of tablet accelerometer / gyro / rotation vector. */
    private fun sampleAndroidImu(): ImuInfo {
        val sm = appContext.getSystemService(SensorManager::class.java)
            ?: return ImuInfo()
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        // Prefer real accelerometer — some head units mis-label TYPE_GRAVITY.
        val sampleSensor = when {
            accel != null -> accel
            gravity != null && gravity.stringType.contains("gravity", ignoreCase = true) -> gravity
            else -> null
        }
        val a = readSensorOnce(sm, sampleSensor, 400L)
        val ax = a?.getOrNull(0)?.toDouble()
        val ay = a?.getOrNull(1)?.toDouble()
        val az = a?.getOrNull(2)?.toDouble()
        val pitch = if (ax != null && ay != null && az != null) {
            Math.toDegrees(atan2(-ax, sqrt(ay * ay + az * az)))
        } else null
        val roll = if (ax != null && ay != null && az != null) {
            Math.toDegrees(atan2(ay, az))
        } else null

        val g = readSensorOnce(sm, gyro, 400L)
        val r = readSensorOnce(sm, rot, 400L)
        var yawRot: Double? = null
        var pitchRot: Double? = null
        var rollRot: Double? = null
        if (r != null && r.size >= 3) {
            val rotMat = FloatArray(9)
            val orient = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rotMat, r)
            SensorManager.getOrientation(rotMat, orient)
            yawRot = Math.toDegrees(orient[0].toDouble())
            pitchRot = Math.toDegrees(orient[1].toDouble())
            rollRot = Math.toDegrees(orient[2].toDouble())
        }

        return ImuInfo(
            accelName = accel?.name,
            gravityName = gravity?.name,
            gyroName = gyro?.name,
            rotationName = rot?.name,
            ax = ax,
            ay = ay,
            az = az,
            pitchDeg = pitch,
            rollDeg = roll,
            gyroX = g?.getOrNull(0)?.toDouble(),
            gyroY = g?.getOrNull(1)?.toDouble(),
            gyroZ = g?.getOrNull(2)?.toDouble(),
            yawRot = yawRot,
            pitchRot = pitchRot,
            rollRot = rollRot,
        )
    }

    private fun readSensorOnce(sm: SensorManager, sensor: Sensor?, timeoutMs: Long): FloatArray? {
        if (sensor == null) return null
        val latch = CountDownLatch(1)
        var values: FloatArray? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (values == null) {
                    values = event.values.copyOf()
                    latch.countDown()
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        return try {
            val ok = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            if (!ok) return null
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            values
        } catch (t: Throwable) {
            Log.w(TAG, "readSensorOnce ${sensor.name}: ${t.message}")
            null
        } finally {
            runCatching { sm.unregisterListener(listener) }
        }
    }

    private fun loadInstance(className: String): Any? {
        return try {
            val cls = Class.forName(className)
            val method = cls.getMethod("getInstance", Context::class.java)
            method.invoke(null, appContext) ?: method.invoke(null, permContext)
        } catch (t: Throwable) {
            val c = (t as? InvocationTargetException)?.cause ?: t
            lastBindError = "${c.javaClass.simpleName}: ${c.message}"
            Log.w(TAG, "loadInstance($className): $lastBindError")
            null
        }
    }

    private fun num(dev: Any, name: String): Double? =
        runCatching {
            val m = dev.javaClass.getMethod(name)
            sanitizeDouble((m.invoke(dev) as? Number)?.toDouble())
        }.getOrNull()

    private fun invokeNum(dev: Any, name: String, arg: Int): Double? =
        runCatching {
            val m = dev.javaClass.getMethod(name, Int::class.javaPrimitiveType)
            sanitizeDouble((m.invoke(dev, arg) as? Number)?.toDouble())
        }.getOrNull()

    private fun invokeString(dev: Any, name: String): String? =
        runCatching {
            (dev.javaClass.getMethod(name).invoke(dev) as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun featureId(fieldName: String): Int? {
        val cls = featureIdsClass ?: return FEATURE_FALLBACKS[fieldName]
        return runCatching { cls.getField(fieldName).getInt(null) }.getOrNull()
            ?: FEATURE_FALLBACKS[fieldName]
    }

    private fun pollFeature(dev: Any, featureId: Int): Int? {
        val evClass = eventValueClass
        if (evClass != null) {
            runCatching {
                val m = dev.javaClass.getMethod("get", IntArray::class.java, Class::class.java)
                val result = m.invoke(dev, intArrayOf(featureId), evClass) ?: return@runCatching null
                extractEventInt(result)
            }.getOrNull()?.let { return it }
        }
        runCatching {
            val m = dev.javaClass.getMethod("get", Int::class.javaPrimitiveType)
            extractEventInt(m.invoke(dev, featureId))
        }.getOrNull()?.let { return it }
        return null
    }

    private fun extractEventInt(result: Any?): Int? {
        if (result == null) return null
        if (result is Number) return sanitizeInt(result.toInt())
        val intV = runCatching {
            result.javaClass.getField("intValue").get(result) as? Number
        }.getOrNull()?.toInt()?.let { sanitizeInt(it) }
        if (intV != null) return intV
        val floatV = runCatching {
            result.javaClass.getField("floatValue").get(result) as? Number
        }.getOrNull()?.toDouble()?.let { sanitizeDouble(it)?.toInt() }
        if (floatV != null) return floatV
        val doubleV = runCatching {
            result.javaClass.getField("doubleValue").get(result) as? Number
        }.getOrNull()?.toDouble()?.let { sanitizeDouble(it)?.toInt() }
        if (doubleV != null) return doubleV
        val arr = runCatching {
            result.javaClass.getField("intArrayValue").get(result) as? IntArray
        }.getOrNull()
        return arr?.firstOrNull()?.let { sanitizeInt(it) }
    }

    private fun sanitizeInt(v: Int): Int? =
        v.takeUnless { it in SENTINEL_I || kotlin.math.abs(v.toLong()) > 100_000_000L }

    private fun sanitizeDouble(v: Double?): Double? {
        if (v == null || !v.isFinite()) return null
        if (v in SENTINEL_D || kotlin.math.abs(v) > 100_000_000.0) return null
        return v
    }

    private fun constInt(cls: Class<*>, name: String): Int? =
        runCatching { cls.getField(name).getInt(null) }.getOrNull()

    private fun decodeGear(raw: Int?): String = when (raw) {
        1 -> "P"
        2 -> "R"
        3 -> "N"
        4 -> "D"
        5 -> "S"
        6 -> "M"
        0 -> "P?"
        null -> "—"
        else -> "raw=$raw"
    }

    private fun fmt(v: Double?, suffix: String = ""): String =
        if (v == null) "—" else trimNum(v) + suffix

    private fun fmt(v: Int?): String = v?.toString() ?: "—"

    private fun fmt1(v: Double): String = String.format("%.1f", v)
    private fun fmt3(v: Double): String = String.format("%.3f", v)

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format("%.1f", v)

    private fun formatAny(v: Any?): String = when (v) {
        null -> "null"
        is Double, is Float -> trimNum((v as Number).toDouble())
        is ByteArray -> "bytes[${v.size}]"
        is IntArray -> v.joinToString(prefix = "[", postfix = "]")
        else -> v.toString().take(40)
    }

    companion object {
        private const val TAG = "BydVehicleInfo"
        /** Plausible remaining usable energy (kWh) for passenger BYDs. */
        private const val USABLE_KWH_MIN = 0.5
        private const val USABLE_KWH_MAX = 100.0
        private const val PACK_KWH_MIN = 5.0
        private const val PACK_KWH_MAX = 100.0

        private val DEVICE_CLASSES = listOf(
            "Statistic" to "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
            "Speed" to "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
            "Gearbox" to "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
            "Sensor" to "android.hardware.bydauto.sensor.BYDAutoSensorDevice",
            "Engine" to "android.hardware.bydauto.engine.BYDAutoEngineDevice",
            "Bodywork" to "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
            "Charging" to "android.hardware.bydauto.charging.BYDAutoChargingDevice",
            "AC" to "android.hardware.bydauto.ac.BYDAutoAcDevice",
            "Instrument" to "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
            "Energy" to "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
            "VehicleHealth" to "android.hardware.bydauto.vehiclehealth.BYDAutoVehicleHealthDevice",
            "CollectData" to "android.hardware.bydauto.collectdata.BYDAutoCollectDataDevice",
        )

        private val STATISTIC_FEATURES = listOf(
            "STATISTIC_AVERAGE_BATTERY_TEMP",
            "STATISTIC_HIGHEST_BATTERY_TEMP",
            "STATISTIC_LOWEST_BATTERY_TEMP",
            "STATISTIC_BATTERY_HEALTHY_INDEX",
            "STATISTIC_INSTANTANEOUS_CURRENT",
            "STATISTIC_HIGHEST_BATTERY_VOLTAGE",
            "STATISTIC_LOWEST_BATTERY_VOLTAGE",
            "STATISTIC_BATTERY_AVAILABLE_POWER",
            "STATISTIC_REMAINING_BATTERY_POWER",
            "STATISTIC_SOC_BATTERY_PERCENTAGE",
            "STATISTIC_WATER_TEMPERATURE",
        )

        private val SENSOR_FEATURES = listOf(
            "SENSOR_AUTO_SLOPE",
            "SENSOR_AUTO_SLOPE_2",
            "SENSOR_YAW_RATE_SIGNAL",
            "SENSOR_YAW_RATE_STATUS",
            "SENSOR_AX_223",
            "SENSOR_AY_223",
        )

        private val FEATURE_FALLBACKS = mapOf(
            "STATISTIC_AVERAGE_BATTERY_TEMP" to 261,
            "STATISTIC_BATTERY_AVAILABLE_POWER" to 262,
            "STATISTIC_BATTERY_HEALTHY_INDEX" to 263,
            "STATISTIC_HIGHEST_BATTERY_TEMP" to 279,
            "STATISTIC_HIGHEST_BATTERY_VOLTAGE" to 280,
            "STATISTIC_INSTANTANEOUS_CURRENT" to 281,
            "STATISTIC_LOWEST_BATTERY_TEMP" to 290,
            "STATISTIC_LOWEST_BATTERY_VOLTAGE" to 291,
            "STATISTIC_REMAINING_BATTERY_POWER" to 313,
            "STATISTIC_SOC_BATTERY_PERCENTAGE" to 314,
            "STATISTIC_WATER_TEMPERATURE" to 328,
            "SENSOR_AUTO_SLOPE" to 1388,
            "SENSOR_AX_223" to 1390,
            "SENSOR_AY_223" to 1394,
            "SENSOR_YAW_RATE_SIGNAL" to 1407,
            "SENSOR_AUTO_SLOPE_2" to 4573,
        )

        private val SENTINEL_I = setOf(
            -1, -2147482645, -2147482646, -2147482647, -2147482648, 65535,
            Int.MIN_VALUE, -999999999, -999_999_999,
        )
        private val SENTINEL_D = setOf(
            -1.0, -2147482645.0, -2147482646.0, -2147482647.0, -2147482648.0, 65535.0,
            -999999999.0, -999_999_999.0,
        )
    }
}
