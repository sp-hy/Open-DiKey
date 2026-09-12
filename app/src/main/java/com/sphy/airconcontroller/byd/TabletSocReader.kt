package com.sphy.airconcontroller.byd

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.sphy.airconcontroller.adb.AdbPermissionManager
import java.io.RandomAccessFile

/**
 * Tablet / head-unit SoC readings (not HV battery SOC).
 *
 * RAM + storage use public Android APIs. System CPU uses `/proc/stat` when readable;
 * on DiLink5 Shark that file is SELinux-blocked for apps, so we fall back to local ADB
 * (`head -1 /proc/stat` + loadavg), same privilege path Overdrive needs for live CPU.
 *
 * GPU busy is not available on this Shark build (no kgsl sysfs — HGSL only).
 */
class TabletSocReader(context: Context) {
    private val app = context.applicationContext
    private var prevCpu: CpuSample? = null
    private var pendingAdbCpuSample: CpuSample? = null
    private var lastLoadAvgPct: Double? = null
    private var adbBlockedUntilElapsed = 0L

    data class Snapshot(
        val cpuPct: Double? = null,
        val ramUsedMb: Long? = null,
        val ramTotalMb: Long? = null,
        val ramPct: Double? = null,
        val storageUsedGb: Double? = null,
        val storageTotalGb: Double? = null,
        val storagePct: Double? = null,
    )

    fun snapshot(): Snapshot {
        val ram = readRam()
        val storage = readStorage()
        refreshCpuViaAdbIfNeeded()
        return Snapshot(
            cpuPct = readCpuPct(),
            ramUsedMb = ram?.usedMb,
            ramTotalMb = ram?.totalMb,
            ramPct = ram?.pct,
            storageUsedGb = storage?.usedGb,
            storageTotalGb = storage?.totalGb,
            storagePct = storage?.pct,
        )
    }

    private data class RamInfo(val usedMb: Long, val totalMb: Long, val pct: Double)

    private fun readRam(): RamInfo? = runCatching {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = mi.totalMem
        if (total <= 0L) return@runCatching null
        val used = (total - mi.availMem).coerceAtLeast(0L)
        RamInfo(
            usedMb = used / (1024L * 1024L),
            totalMb = total / (1024L * 1024L),
            pct = used * 100.0 / total,
        )
    }.getOrNull()

    private data class StorageInfo(val usedGb: Double, val totalGb: Double, val pct: Double)

    private fun readStorage(): StorageInfo? = runCatching {
        val path = Environment.getDataDirectory()
        val stat = StatFs(path.absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        val avail = stat.availableBlocksLong * stat.blockSizeLong
        if (total <= 0L) return@runCatching null
        val used = (total - avail).coerceAtLeast(0L)
        StorageInfo(
            usedGb = used / (1024.0 * 1024.0 * 1024.0),
            totalGb = total / (1024.0 * 1024.0 * 1024.0),
            pct = used * 100.0 / total,
        )
    }.getOrNull()

    private data class CpuSample(val idle: Long, val total: Long)

    private fun readCpuPct(): Double? {
        val sample = readCpuSampleFromProc() ?: pendingAdbCpuSample
        if (sample != null) {
            val prev = prevCpu
            prevCpu = sample
            pendingAdbCpuSample = null
            if (prev != null) {
                val idleDelta = (sample.idle - prev.idle).coerceAtLeast(0L)
                val totalDelta = (sample.total - prev.total).coerceAtLeast(0L)
                if (totalDelta > 0L) {
                    val busy = 1.0 - (idleDelta.toDouble() / totalDelta.toDouble())
                    return (busy * 100.0).coerceIn(0.0, 100.0)
                }
            }
        }
        return lastLoadAvgPct
    }

    private fun readCpuSampleFromProc(): CpuSample? = runCatching {
        RandomAccessFile("/proc/stat", "r").use { raf ->
            parseCpuStatLine(raf.readLine())
        }
    }.getOrNull()

    private fun refreshCpuViaAdbIfNeeded() {
        if (readCpuSampleFromProc() != null) return

        val now = SystemClock.elapsedRealtime()
        if (now < adbBlockedUntilElapsed) return
        if (!AdbPermissionManager.isPortOpen()) {
            adbBlockedUntilElapsed = now + ADB_FAIL_COOLDOWN_MS
            return
        }

        val result = AdbPermissionManager.runShellCommandQuick(
            app,
            "head -1 /proc/stat; echo __LOAD__; cat /proc/loadavg",
        )
        if (result.exitCode != 0 && result.output.isBlank()) {
            Log.d(TAG, "adb cpu probe failed: ${result.output}")
            adbBlockedUntilElapsed = now + ADB_FAIL_COOLDOWN_MS
            return
        }
        parseAdbProbe(result.output)
    }

    private fun parseAdbProbe(output: String) {
        val sections = output.split("__LOAD__")
        val statLine = sections.getOrNull(0)?.lineSequence()?.firstOrNull { it.startsWith("cpu ") }
        parseCpuStatLine(statLine)?.let { pendingAdbCpuSample = it }

        val loadLine = sections.getOrNull(1)?.trim()?.lineSequence()?.firstOrNull()
        if (loadLine != null) {
            val load1 = loadLine.trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull()
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            if (load1 != null) {
                lastLoadAvgPct = ((load1 / cores) * 100.0).coerceIn(0.0, 100.0)
            }
        }
    }

    private fun parseCpuStatLine(line: String?): CpuSample? {
        if (line.isNullOrBlank()) return null
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || parts[0] != "cpu" || parts.size < 5) return null
        val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 4) return null
        val idle = nums[3] + nums.getOrElse(4) { 0L }
        val total = nums.sum()
        return CpuSample(idle = idle, total = total)
    }

    companion object {
        private const val TAG = "TabletSocReader"
        private const val ADB_FAIL_COOLDOWN_MS = 10_000L
    }
}
