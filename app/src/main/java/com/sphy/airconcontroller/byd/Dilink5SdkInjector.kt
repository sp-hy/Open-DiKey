package com.sphy.airconcontroller.byd

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * Load the OEM `bydauto` classes at runtime by injecting the already-installed
 * `com.byd.data.collect` APK into this app's classloader. Same approach as trip-stats
 * Dilink5SdkInjector: the proprietary SDK is never bundled or copied to disk.
 */
object Dilink5SdkInjector {
    private const val TAG = "Dilink5SdkInjector"
    private val PROBE_CLASSES = listOf(
        "android.hardware.bydauto.ac.BYDAutoAcDevice",
        "android.hardware.bydauto.statistic.BYDAutoStatisticDevice",
    )
    private const val OEM_PKG = "com.byd.data.collect"

    @Volatile
    private var permanentlyUnavailable = false

    private var pristineLoader: ClassLoader? = null
    private var pristineDexElements: Array<*>? = null

    @Synchronized
    fun ensure(context: Context): Boolean {
        val loader = context.classLoader
        if (loadable(loader)) return true
        if (permanentlyUnavailable) return false

        val apkPaths = oemApkPaths(context)
        if (apkPaths.isEmpty()) {
            Log.w(TAG, "$OEM_PKG not found / no apk path")
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

            val ok = loadable(loader)
            Log.i(TAG, "injected ${newEls.size} dex element(s); bydauto loadable=$ok")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "inject failed: ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    fun isLoadable(context: Context): Boolean = loadable(context.classLoader)

    private fun loadable(loader: ClassLoader): Boolean =
        PROBE_CLASSES.any { runCatching { Class.forName(it, false, loader) }.isSuccess }

    private fun oemApkPaths(context: Context): List<String> = runCatching {
        val ai = context.packageManager.getApplicationInfo(OEM_PKG, 0)
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
