package com.sphy.airconcontroller.byd

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * Load the OEM `bydauto` classes at runtime by injecting already-installed OEM APKs
 * into this app's classloader. The proprietary SDK is never bundled or copied to disk.
 *
 * Package order matters (first match wins for not-yet-loaded classes):
 * 1. [OEM_CARSETTINGS] — newer DiPilot (SpeedAdjustMode / SAM, etc.)
 * 2. [OEM_DATA_COLLECT] — HVAC / climate baseline used by other controllers
 */
object Dilink5SdkInjector {
    private const val TAG = "Dilink5SdkInjector"
    private val PROBE_CLASSES = listOf(
        "android.hardware.bydauto.ac.BYDAutoAcDevice",
        "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
        "android.hardware.bydauto.sensor.BYDAutoSensorDevice",
        "android.hardware.bydauto.light.BYDAutoLightDevice",
        "android.hardware.bydauto.setting.BYDAutoSettingDevice",
        "android.hardware.bydauto.seat.BYDAutoSeatDevice",
        "android.hardware.bydauto.tyre.BYDAutoTyreDevice",
        "android.hardware.bydauto.speed.BYDAutoSpeedDevice",
        "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice",
        "android.hardware.bydauto.engine.BYDAutoEngineDevice",
        "android.hardware.bydauto.charging.BYDAutoChargingDevice",
        "android.hardware.bydauto.energy.BYDAutoEnergyDevice",
        "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
        "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice",
        "android.hardware.bydauto.vehiclehealth.BYDAutoVehicleHealthDevice",
        "android.hardware.bydauto.collectdata.BYDAutoCollectDataDevice",
        "android.hardware.bydauto.dipilot.BYDAutoDiPilotDevice",
        "android.hardware.bydauto.adas.BYDAutoADASDevice",
    )

    /** Prefer CarSettings DiPilot (has SAM) over data.collect (ACC only). */
    private val OEM_PACKAGES = listOf(
        "com.byd.carsettings",
        "com.byd.data.collect",
    )

    @Volatile
    private var permanentlyUnavailable = false

    private var pristineLoader: ClassLoader? = null
    private var pristineDexElements: Array<*>? = null

    /** Packages whose APK paths have already been merged into the classloader. */
    private val injectedPackages = linkedSetOf<String>()

    @Synchronized
    fun ensure(context: Context): Boolean {
        val loader = context.classLoader
        if (permanentlyUnavailable) return false

        val missing = OEM_PACKAGES.filter { pkg ->
            pkg !in injectedPackages && oemApkPaths(context, pkg).isNotEmpty()
        }

        // Already injected everything we can and at least one probe class loads.
        if (missing.isEmpty()) {
            return loadable(loader).also { ok ->
                if (!ok) {
                    Log.w(TAG, "OEM packages injected but no bydauto probe class loadable")
                }
            }
        }

        val apkPaths = OEM_PACKAGES.flatMap { pkg ->
            oemApkPaths(context, pkg).also { paths ->
                if (paths.isNotEmpty()) {
                    Log.i(TAG, "oem $pkg → ${paths.size} apk path(s)")
                }
            }
        }.distinct()

        if (apkPaths.isEmpty()) {
            Log.w(TAG, "no OEM bydauto APKs found (${OEM_PACKAGES.joinToString()})")
            permanentlyUnavailable = true
            return false
        }

        return try {
            val baseCl = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathListF = baseCl.getDeclaredField("pathList").apply { isAccessible = true }
            val pathList = pathListF.get(loader)
            val dexListCls = pathList.javaClass
            val dexElementsF = dexListCls.getDeclaredField("dexElements").apply { isAccessible = true }
            val old = dexElementsF.get(pathList) as Array<*>
            val base = if (pristineLoader === loader) pristineDexElements!! else old.also {
                pristineLoader = loader
                pristineDexElements = it
            }

            val suppressed = ArrayList<IOException>()
            val newEls = makeInMemoryElements(dexListCls, apkPaths, suppressed)
                ?: makeElements(
                    dexListCls,
                    apkPaths.map { File(it) },
                    File(context.codeCacheDir, "bydauto-inj").apply { mkdirs() },
                    suppressed
                )
                ?: return false.also { Log.w(TAG, "no dex-element builder found") }
            suppressed.forEach { Log.w(TAG, "suppressed: $it") }

            val comp = requireNotNull(base.javaClass.componentType) { "dexElements is not an array" }
            val combined = java.lang.reflect.Array.newInstance(comp, base.size + newEls.size)
            System.arraycopy(base, 0, combined, 0, base.size)
            System.arraycopy(newEls, 0, combined, base.size, newEls.size)
            dexElementsF.set(pathList, combined)

            OEM_PACKAGES.forEach { pkg ->
                if (oemApkPaths(context, pkg).isNotEmpty()) injectedPackages.add(pkg)
            }

            val ok = loadable(loader)
            val hasSam = diPilotHasMethod(loader, "getSpeedAdjustModeState")
            Log.i(
                TAG,
                "injected ${newEls.size} dex element(s) from ${injectedPackages.joinToString()}; " +
                    "bydauto loadable=$ok diPilotHasSAM=$hasSam",
            )
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "inject failed: ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    fun isLoadable(context: Context): Boolean = loadable(context.classLoader)

    /** True if the loaded DiPilot class exposes SpeedAdjustMode (CarSettings SDK). */
    fun diPilotHasSpeedAdjust(context: Context): Boolean =
        diPilotHasMethod(context.classLoader, "getSpeedAdjustModeState")

    private fun diPilotHasMethod(loader: ClassLoader, method: String): Boolean =
        runCatching {
            val cls = Class.forName("android.hardware.bydauto.dipilot.BYDAutoDiPilotDevice", false, loader)
            cls.methods.any { it.name == method }
        }.getOrDefault(false)

    private fun loadable(loader: ClassLoader): Boolean =
        PROBE_CLASSES.any { runCatching { Class.forName(it, false, loader) }.isSuccess }

    private fun oemApkPaths(context: Context, packageName: String): List<String> = runCatching {
        val ai = context.packageManager.getApplicationInfo(packageName, 0)
        buildList {
            ai.sourceDir?.let { add(it) }
            ai.splitSourceDirs?.let { addAll(it) }
        }.distinct()
    }.getOrDefault(emptyList())

    private fun makeInMemoryElements(
        dexListCls: Class<*>,
        apkPaths: List<String>,
        suppressed: MutableList<IOException>
    ): Array<*>? {
        val m = runCatching {
            dexListCls.getDeclaredMethod(
                "makeInMemoryDexElements",
                Array<ByteBuffer>::class.java,
                List::class.java
            ).apply { isAccessible = true }
        }.getOrNull() ?: return null

        val buffers = apkPaths.flatMap { path ->
            runCatching {
                ZipFile(path).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                        .map { entry -> ByteBuffer.wrap(zip.getInputStream(entry).readBytes()) }
                        .toList()
                }
            }.getOrElse { e ->
                suppressed.add(IOException("read $path: ${e.message}", e))
                emptyList()
            }
        }
        if (buffers.isEmpty()) return null

        return m.invoke(null, buffers.toTypedArray(), suppressed) as Array<*>
    }

    private fun makeElements(
        dexListCls: Class<*>,
        files: List<File>,
        optDir: File,
        suppressed: MutableList<IOException>
    ): Array<*>? {
        runCatching {
            val m = dexListCls.getDeclaredMethod(
                "makePathElements",
                List::class.java,
                File::class.java,
                List::class.java
            ).apply { isAccessible = true }
            return m.invoke(null, files, optDir, suppressed) as Array<*>
        }
        runCatching {
            val m = dexListCls.getDeclaredMethod(
                "makeDexElements",
                List::class.java,
                File::class.java,
                List::class.java,
                ClassLoader::class.java
            ).apply { isAccessible = true }
            return m.invoke(
                null,
                files,
                optDir,
                suppressed,
                Dilink5SdkInjector::class.java.classLoader
            ) as Array<*>
        }
        return null
    }
}
