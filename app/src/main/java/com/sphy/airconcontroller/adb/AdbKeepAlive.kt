package com.sphy.airconcontroller.adb

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.InvocationTargetException

/**
 * Keeps DiLink ADB reachable after reboot — same values as
 * [BYD ADB Rescue](https://github.com/DottoreTozzi/BYD-ADB-Unlock):
 *
 * - `Settings.Global.adb_enabled = 1` (needs [WRITE_SECURE_SETTINGS])
 * - `persist.sys.adb.wiress.enable = true` (BYD's deliberate misspelling)
 * - `sys.connect.adb.wiress = 1`
 *
 * Standard ADB can be restored without `adbd` already listening. Wireless props
 * are best-effort (SELinux may block); when local ADB is up we also `setprop` via shell.
 */
object AdbKeepAlive {
    private const val TAG = "AdbKeepAlive"

    private const val ADB_ENABLED = "adb_enabled"
    private const val BYD_WIRELESS_ENABLED = "persist.sys.adb.wiress.enable"
    private const val BYD_WIRELESS_CONNECTION = "sys.connect.adb.wiress"

    data class Result(
        val adbBefore: Int,
        val adbAfter: Int,
        val adbWrote: Boolean,
        val wirelessEnabledBefore: String,
        val wirelessEnabledAfter: String,
        val wirelessConnectionBefore: String,
        val wirelessConnectionAfter: String,
        val hasSecureSettings: Boolean,
    ) {
        val ok: Boolean
            get() = adbAfter == 1 &&
                isTruthy(wirelessEnabledAfter) &&
                wirelessConnectionAfter == "1"
    }

    fun hasSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Restore both ADB modes. Safe to call from boot receivers (no local ADB required
     * for `adb_enabled`). Optionally reinforces via shell when [viaShell] is true and
     * port 5555 is open.
     */
    fun ensure(context: Context, trigger: String, viaShell: Boolean = true): Result {
        val app = context.applicationContext
        val permission = hasSecureSettings(app)

        val adbBefore = readAdbEnabled(app)
        var adbWrote = false
        if (adbBefore != 1 && permission) {
            adbWrote = Settings.Global.putInt(app.contentResolver, ADB_ENABLED, 1)
        }
        val adbAfter = readAdbEnabled(app)

        val weBefore = readProp(BYD_WIRELESS_ENABLED)
        if (!isTruthy(weBefore)) {
            SystemPropertiesCompat.set(BYD_WIRELESS_ENABLED, "true")
        }
        val wcBefore = readProp(BYD_WIRELESS_CONNECTION)
        if (wcBefore != "1") {
            SystemPropertiesCompat.set(BYD_WIRELESS_CONNECTION, "1")
        }

        if (viaShell && AdbPermissionManager.isPortOpen()) {
            runCatching {
                // Reinforce through shell (more likely to stick for setprop on DiLink).
                AdbPermissionManager.runShellCommandBlocking(
                    app,
                    "settings put global adb_enabled 1 ; " +
                        "setprop $BYD_WIRELESS_ENABLED true ; " +
                        "setprop $BYD_WIRELESS_CONNECTION 1",
                )
            }
        }

        val result = Result(
            adbBefore = adbBefore,
            adbAfter = readAdbEnabled(app),
            adbWrote = adbWrote,
            wirelessEnabledBefore = weBefore,
            wirelessEnabledAfter = readProp(BYD_WIRELESS_ENABLED),
            wirelessConnectionBefore = wcBefore,
            wirelessConnectionAfter = readProp(BYD_WIRELESS_CONNECTION),
            hasSecureSettings = permission,
        )
        Log.i(
            TAG,
            "$trigger adb ${result.adbBefore}->${result.adbAfter} wrote=$adbWrote " +
                "perm=$permission wiress ${result.wirelessEnabledBefore}->${result.wirelessEnabledAfter} " +
                "conn ${result.wirelessConnectionBefore}->${result.wirelessConnectionAfter}",
        )
        return result
    }

    suspend fun ensureAsync(context: Context, trigger: String): Result =
        withContext(Dispatchers.IO) { ensure(context, trigger) }

    private fun readAdbEnabled(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, ADB_ENABLED, -1)

    private fun readProp(key: String): String = SystemPropertiesCompat.get(key, "")

    private fun isTruthy(value: String): Boolean =
        value.equals("1", ignoreCase = true) ||
            value.equals("true", ignoreCase = true) ||
            value.equals("y", ignoreCase = true) ||
            value.equals("yes", ignoreCase = true) ||
            value.equals("on", ignoreCase = true)
}

/** Reflective access to [android.os.SystemProperties] (hidden API). */
internal object SystemPropertiesCompat {
    private const val TAG = "SysProps"
    private const val CLASS_NAME = "android.os.SystemProperties"

    fun get(key: String, fallback: String): String =
        try {
            val type = Class.forName(CLASS_NAME)
            val method = type.getDeclaredMethod("get", String::class.java, String::class.java)
            method.isAccessible = true
            method.invoke(null, key, fallback)?.toString() ?: fallback
        } catch (t: Throwable) {
            Log.d(TAG, "get $key failed: ${root(t).message}")
            fallback
        }

    fun set(key: String, value: String): Boolean =
        try {
            val type = Class.forName(CLASS_NAME)
            val method = type.getDeclaredMethod("set", String::class.java, String::class.java)
            method.isAccessible = true
            method.invoke(null, key, value)
            true
        } catch (t: Throwable) {
            Log.d(TAG, "set $key failed: ${root(t).message}")
            false
        }

    private fun root(t: Throwable): Throwable =
        (t as? InvocationTargetException)?.cause ?: t
}
