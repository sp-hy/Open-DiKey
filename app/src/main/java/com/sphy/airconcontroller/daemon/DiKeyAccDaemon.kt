package com.sphy.airconcontroller.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shell-uid keep-alive (Overdrive / dashcast `app_process` pattern).
 *
 * DiLink force-stops third-party apps (`stopped=true`), which blocks boot/ACC receivers
 * inside the app process. This daemon runs as uid 2000:
 * ```
 * CLASSPATH=<apk> app_process64 /system/bin com.sphy.airconcontroller.daemon.DiKeyAccDaemon
 * ```
 * and explicitly starts [com.sphy.airconcontroller.boot.DiKeyWakeActivity] to clear
 * the stopped flag and bring up the DiKey listener.
 *
 * Note: on Shark / DiLink 5, `/data/local/tmp` is not writable — logs go to `/sdcard`.
 */
object DiKeyAccDaemon {
    private const val TAG = "DiKeyAccDaemon"
    private const val PKG = "com.sphy.airconcontroller"
    private const val WAKE_COMPONENT = "$PKG/.boot.DiKeyWakeActivity"
    private const val SERVICE_COMPONENT = "$PKG/.boot.DiKeyListenService"
    private const val POLL_MS = 3_000L

    private val waking = AtomicBoolean(false)

    @JvmStatic
    fun main(args: Array<String>) {
        Log.i(TAG, "started uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}")
        try {
            Looper.prepareMainLooper()
            val context = systemContext()
            if (context != null) {
                registerWakeReceiver(context)
                Log.i(TAG, "ACC/boot broadcast receiver registered")
            } else {
                Log.w(TAG, "no system context — poll-only mode")
            }
            // Catch-up if we attach while the HU is already awake and the app is stopped
            // or the listener isn't running.
            if (isHeadUnitAwake() && needsWake()) {
                wakeListener("startup-catchup")
            }
            Thread({
                while (true) {
                    try {
                        if (isHeadUnitAwake() && needsWake()) {
                            wakeListener("poll-awake")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "poll error: ${t.message}")
                    }
                    try {
                        Thread.sleep(POLL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "dikey-acc-poll").apply { isDaemon = true }.start()
            Looper.loop()
        } catch (t: Throwable) {
            Log.e(TAG, "daemon crashed: ${t.message}", t)
            // Last-resort poll loop if Looper setup fails.
            while (true) {
                try {
                    if (isHeadUnitAwake() && needsWake()) wakeListener("fallback-poll")
                } catch (_: Throwable) {
                }
                Thread.sleep(POLL_MS)
            }
        }
    }

    private fun registerWakeReceiver(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction(Intent.ACTION_LOCKED_BOOT_COMPLETED)
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction("android.intent.action.QUICKBOOT_POWERON")
            addAction("com.htc.intent.action.QUICKBOOT_POWERON")
            addAction("com.byd.action.ACC_ON")
            addAction("com.byd.action.IGN_ON")
            addAction("com.byd.accmode.ACC_MODE_CHANGED")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                Log.i(TAG, "broadcast $action")
                if (action == "com.byd.action.ACC_OFF" || action == "com.byd.action.IGN_OFF") return
                wakeListener("broadcast:$action")
            }
        }
        try {
            context.registerReceiver(receiver, filter)
        } catch (t: Throwable) {
            // API 33+ may need flags; fall back via reflection.
            try {
                val method = Context::class.java.getMethod(
                    "registerReceiver",
                    BroadcastReceiver::class.java,
                    IntentFilter::class.java,
                    Int::class.javaPrimitiveType,
                )
                method.invoke(context, receiver, filter, 0x2) // RECEIVER_EXPORTED
                Log.i(TAG, "registered with RECEIVER_EXPORTED")
            } catch (t2: Throwable) {
                Log.w(TAG, "registerReceiver failed: ${t.message} / ${t2.message}")
            }
        }
    }

    private fun systemContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        at.getMethod("getSystemContext").invoke(thread) as Context
    } catch (t: Throwable) {
        Log.w(TAG, "ActivityThread.systemMain failed: ${t.message}")
        null
    }

    private fun wakeListener(reason: String) {
        if (!waking.compareAndSet(false, true)) return
        Thread({
            try {
                Log.i(TAG, "wake ($reason)")
                // Activity start clears FLAG_STOPPED; FGS alone cannot when force-stopped.
                shell(
                    "am start --user 0 -n $WAKE_COMPONENT " +
                        "--activity-no-animation --activity-exclude-from-recents --activity-clear-top"
                )
                shell("am start-foreground-service --user 0 -n $SERVICE_COMPONENT")
                shell("am startservice --user 0 -n $SERVICE_COMPONENT")
            } finally {
                Thread.sleep(5_000L)
                waking.set(false)
            }
        }, "dikey-wake").start()
    }

    private fun needsWake(): Boolean =
        isPackageStopped() || !isListenerAlive()

    private fun isPackageStopped(): Boolean =
        shell("dumpsys package $PKG").contains("stopped=true")

    /** True if our package process is running (listener / UI). */
    private fun isListenerAlive(): Boolean {
        val out = shell("pidof $PKG").trim()
        return out.isNotEmpty() && out != "0"
    }

    private fun isHeadUnitAwake(): Boolean {
        val power = shell("dumpsys power")
        if (power.contains("mWakefulness=Awake", ignoreCase = true)) return true
        if (power.contains("mHoldingDisplaySuspendBlocker=true")) return true
        // USB host with our bridge present is a strong "vehicle up" signal.
        val usb = shell("dumpsys usb")
        if (usb.contains("Espressif", ignoreCase = true)) return true
        if (usb.contains("USB JTAG", ignoreCase = true)) return true
        return false
    }

    private fun shell(command: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val finished = p.waitFor(8, TimeUnit.SECONDS)
            val stdout = p.inputStream.bufferedReader().use { it.readText() }
            val stderr = p.errorStream.bufferedReader().use { it.readText() }
            if (!finished) {
                p.destroyForcibly()
                return stdout
            }
            if (p.exitValue() != 0 && stderr.isNotBlank()) {
                Log.d(TAG, "shell err: ${stderr.take(200)}")
            }
            stdout
        } catch (e: Exception) {
            Log.w(TAG, "shell failed: ${e.message}")
            ""
        }
    }
}
