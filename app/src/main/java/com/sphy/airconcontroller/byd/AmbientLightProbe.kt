package com.sphy.airconcontroller.byd

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.sphy.airconcontroller.lighting.LightingPeriod
import java.lang.reflect.InvocationTargetException

/**
 * Detects day vs night / low-light using the vehicle ambient light sensor when available
 * ([BYDAutoSensorDevice.getLightIntensity]), with fallbacks to low-beam status and the
 * Android light sensor. Same ambient-light signal the car uses for auto headlights / tunnels.
 */
class AmbientLightProbe(context: Context) {
    private val appContext = context.applicationContext
    private val permContext = BydPermissionContext(appContext)

    @Volatile private var sensorDevice: Any? = null
    @Volatile private var lightDevice: Any? = null
    @Volatile private var lastAndroidLux: Float? = null
    @Volatile private var lastPeriod: LightingPeriod? = null
    @Volatile var lastSource: String = "unknown"
        private set
    @Volatile var lastDetail: String = "—"
        private set

    private val androidListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
                lastAndroidLux = event.values.firstOrNull()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun startAndroidSensor() {
        val sm = appContext.getSystemService(SensorManager::class.java) ?: return
        val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        sm.registerListener(androidListener, light, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stopAndroidSensor() {
        val sm = appContext.getSystemService(SensorManager::class.java) ?: return
        sm.unregisterListener(androidListener)
    }

    /**
     * Resolve current lighting period with hysteresis so tunnels / dusk don't flicker.
     * Darker → night; brighter → day. Mid levels keep the previous period.
     *
     * [Resolution.reliable] is false when only the clock fallback is available — callers
     * should defer color apply until a cabin sensor answers (cold boot).
     */
    fun resolvePeriod(previous: LightingPeriod?): Resolution {
        val bydLevel = bydIllumLevel()
        if (bydLevel != null) {
            lastSource = "vehicle"
            lastDetail = "illum level $bydLevel"
            val next = periodFromIllum(bydLevel, previous)
            lastPeriod = next
            return Resolution(next, reliable = true)
        }

        val lowBeam = bydLowBeamOn()
        if (lowBeam != null) {
            lastSource = "headlights"
            lastDetail = if (lowBeam) "low beam on" else "low beam off"
            val next = if (lowBeam) LightingPeriod.NIGHT else LightingPeriod.DAY
            lastPeriod = next
            return Resolution(next, reliable = true)
        }

        val lux = lastAndroidLux
        if (lux != null) {
            lastSource = "phone sensor"
            lastDetail = "%.0f lx".format(lux)
            val next = periodFromLux(lux, previous)
            lastPeriod = next
            return Resolution(next, reliable = true)
        }

        // Last resort: local clock (no geography hardcoding).
        lastSource = "clock"
        val hour = java.time.LocalTime.now().hour
        lastDetail = "%02d:00".format(hour)
        val next = if (hour in 7..17) LightingPeriod.DAY else LightingPeriod.NIGHT
        lastPeriod = next
        return Resolution(next, reliable = false)
    }

    data class Resolution(val period: LightingPeriod, val reliable: Boolean)

    private fun periodFromIllum(level: Int, previous: LightingPeriod?): LightingPeriod {
        // LEVEL1 brightest … LEVEL5 darkest (<80 lux). Mid band keeps prior state.
        return when {
            level >= 5 -> LightingPeriod.NIGHT
            level <= 2 -> LightingPeriod.DAY
            level == 4 -> LightingPeriod.NIGHT
            level == 3 -> previous ?: lastPeriod ?: LightingPeriod.DAY
            else -> previous ?: lastPeriod ?: LightingPeriod.DAY
        }
    }

    private fun periodFromLux(lux: Float, previous: LightingPeriod?): LightingPeriod =
        when {
            lux < 80f -> LightingPeriod.NIGHT
            lux > 180f -> LightingPeriod.DAY
            else -> previous ?: lastPeriod ?: if (lux < 130f) LightingPeriod.NIGHT else LightingPeriod.DAY
        }

    private fun bydIllumLevel(): Int? {
        val dev = bindSensor() ?: return null
        return try {
            val raw = dev.javaClass.getMethod("getLightIntensity").invoke(dev) as? Number ?: return null
            val level = raw.toInt()
            if (level in 1..5) level else mapIllumConstant(dev, level)
        } catch (t: Throwable) {
            Log.d(TAG, "getLightIntensity failed: ${root(t).message}")
            null
        }
    }

    private fun mapIllumConstant(dev: Any, raw: Int): Int? {
        val names = arrayOf(
            "LIGHT_ILLUM_LEVEL1",
            "LIGHT_ILLUM_LEVEL2",
            "LIGHT_ILLUM_LEVEL3",
            "LIGHT_ILLUM_LEVEL4",
            "LIGHT_ILLUM_LEVEL5"
        )
        for ((index, name) in names.withIndex()) {
            val v = constInt(dev, name) ?: continue
            if (v == raw) return index + 1
        }
        return null
    }

    private fun bydLowBeamOn(): Boolean? {
        val dev = bindLight() ?: return null
        return try {
            val type = constInt(dev, "LIGHT_LOW_BEAM") ?: 2
            val on = constInt(dev, "LIGHT_ON") ?: 1
            val status = dev.javaClass.methods.firstOrNull {
                it.name == "getLightStatus" && it.parameterCount == 1
            }?.invoke(dev, type) as? Number ?: return null
            status.toInt() == on || status.toInt() != 0 && on == 1 && status.toInt() > 0
        } catch (t: Throwable) {
            Log.d(TAG, "getLightStatus failed: ${root(t).message}")
            null
        }
    }

    private fun bindSensor(): Any? {
        sensorDevice?.let { return it }
        Dilink5SdkInjector.ensure(appContext)
        sensorDevice = getInstance(SENSOR_CLASS)
        return sensorDevice
    }

    private fun bindLight(): Any? {
        lightDevice?.let { return it }
        Dilink5SdkInjector.ensure(appContext)
        lightDevice = getInstance(LIGHT_CLASS)
        return lightDevice
    }

    private fun getInstance(className: String): Any? =
        try {
            val cls = Class.forName(className)
            val method = cls.getMethod("getInstance", Context::class.java)
            method.invoke(null, permContext) ?: method.invoke(null, appContext)
        } catch (t: Throwable) {
            Log.d(TAG, "getInstance($className): ${root(t).message}")
            null
        }

    private fun constInt(dev: Any, name: String): Int? {
        var cls: Class<*>? = dev.javaClass
        while (cls != null) {
            runCatching { cls!!.getField(name).getInt(null) }.getOrNull()?.let { return it }
            runCatching {
                cls!!.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
            }.getOrNull()?.let { return it }
            cls = cls.superclass
        }
        return null
    }

    private fun root(t: Throwable): Throwable =
        (t as? InvocationTargetException)?.cause ?: t

    companion object {
        private const val TAG = "AmbientLightProbe"
        private const val SENSOR_CLASS = "android.hardware.bydauto.sensor.BYDAutoSensorDevice"
        private const val LIGHT_CLASS = "android.hardware.bydauto.light.BYDAutoLightDevice"
    }
}
