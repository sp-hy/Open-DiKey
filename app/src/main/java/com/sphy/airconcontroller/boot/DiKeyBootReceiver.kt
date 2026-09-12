package com.sphy.airconcontroller.boot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.sphy.airconcontroller.adb.AdbKeepAlive
import com.sphy.airconcontroller.adb.AdbPermissionManager

/**
 * Starts [DiKeyListenService] on vehicle boot / ACC / unlock, then schedules
 * process-surviving re-kicks (DiLink often isn't ready for USB at first broadcast).
 *
 * Pattern mirrors byd-trip-stats / Overdrive DiLink 5: boot+ACC → sticky FGS + delayed restarts.
 */
class DiKeyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in ACTIONS) return
        if (action in OFF_ACTIONS) {
            Log.i(TAG, "ACC/power off ($action) — leave listener; USB may drop until next ON")
            return
        }
        Log.i(TAG, "wake listener for $action")
        // Restore adb_enabled before anything that needs localhost:5555.
        AdbKeepAlive.ensure(context, "boot:$action", viaShell = false)
        DiKeyListenService.start(context)
        DiKeyAutostart.scheduleRestarts(context)
        DiKeyAutostart.refreshBackgroundGrantsAsync(
            context,
            forceDaemonRestart = action == Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }

    companion object {
        private const val TAG = "DiKeyBoot"

        /** Wakes that should start / re-arm the listener. */
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_POWER_CONNECTED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            // BYD DiLink ACC / ignition (trip-stats + Overdrive)
            "com.byd.action.ACC_ON",
            "com.byd.action.IGN_ON",
            "com.byd.accmode.ACC_MODE_CHANGED",
            "com.byd.action.ACC_OFF",
            "com.byd.action.IGN_OFF",
        )

        private val OFF_ACTIONS = setOf(
            "com.byd.action.ACC_OFF",
            "com.byd.action.IGN_OFF",
        )
    }
}

/**
 * AlarmManager-backed restarts so the listener comes back after DiLink kills the
 * process or USB appears late. Survives process death (unlike Handler.postDelayed).
 */
class DiKeyRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DiKeyAutostart.ACTION_RESTART) return
        val attempt = intent.getIntExtra(DiKeyAutostart.EXTRA_ATTEMPT, -1)
        Log.i(TAG, "scheduled restart #$attempt")
        AdbKeepAlive.ensure(context, "restart:$attempt", viaShell = false)
        DiKeyListenService.start(context)
        DiKeyAutostart.refreshBackgroundGrantsAsync(context)
    }

    companion object {
        private const val TAG = "DiKeyRestart"
    }
}

object DiKeyAutostart {
    const val ACTION_RESTART = "com.sphy.airconcontroller.action.DIKEY_RESTART"
    const val EXTRA_ATTEMPT = "attempt"

    /** Delays after each wake — USB host / adbd often come up after ACC_ON. */
    private val RESTART_DELAYS_MS = longArrayOf(15_000L, 45_000L, 120_000L)

    fun scheduleRestarts(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val now = SystemClock.elapsedRealtime()
        RESTART_DELAYS_MS.forEachIndexed { index, delayMs ->
            val pi = pendingRestart(app, index)
            try {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    now + delayMs,
                    pi,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "exact alarm denied, falling back: ${e.message}")
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    now + delayMs,
                    pi,
                )
            }
        }
        Log.i(TAG, "scheduled ${RESTART_DELAYS_MS.size} listener restarts")
    }

    fun scheduleImmediateRestart(context: Context, delayMs: Long = 2_000L) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingRestart(app, requestCode = 100)
        try {
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pi,
            )
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pi,
            )
        }
    }

    fun refreshBackgroundGrantsAsync(context: Context, forceDaemonRestart: Boolean = false) {
        val app = context.applicationContext
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    AdbKeepAlive.ensure(app, "grants", viaShell = true)
                    AdbPermissionManager.ensureVehicleApiAccess(app)
                    AdbPermissionManager.ensureAutostartWhitelist(app)
                    AdbPermissionManager.ensureAccDaemon(app, forceRestart = forceDaemonRestart)
                }
            }.onFailure { Log.w(TAG, "grant refresh failed: ${it.message}") }
        }.start()
    }

    private fun pendingRestart(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, DiKeyRestartReceiver::class.java)
            .setAction(ACTION_RESTART)
            .putExtra(EXTRA_ATTEMPT, requestCode)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val TAG = "DiKeyAutostart"
}
