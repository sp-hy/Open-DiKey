package com.sphy.airconcontroller.dikey

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.storage.UpOpenApp
import java.util.concurrent.Executors

/**
 * Pushing a DiKey button upwards → user mappings. For now that is open-app only.
 * Pushing downwards stays climate in [DiKeyClimateMapper].
 */
class DiKeyUpMapper(
    private val app: Context,
    private val settings: AppSettings,
    private val onLog: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    fun handle(event: DiKeyEvent) {
        if (event !is DiKeyEvent.Button) return
        if (event.direction != "UP") return
        if (event.event != "CLICK") return
        val mapping = settings.upOpenApp(event.logicalId) ?: return
        main.post { launch(event.logicalId, mapping) }
    }

    private fun launch(buttonId: Int, mapping: UpOpenApp) {
        val intent = app.packageManager.getLaunchIntentForPackage(mapping.packageName)
        if (intent == null) {
            onLog("Buttons · btn$buttonId UP · ${mapping.label} is not installed")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val foreground = isAppForeground()
        if (foreground) {
            if (startActivity(intent)) {
                onLog("Buttons · btn$buttonId UP · open ${mapping.label}")
                return
            }
        }
        val component = intent.component?.flattenToShortString()
        io.execute {
            val viaAdb = AdbPermissionManager.launchComponent(app, mapping.packageName, component)
            main.post {
                if (viaAdb) {
                    onLog("Buttons · btn$buttonId UP · open ${mapping.label}")
                } else if (!foreground && startActivity(intent)) {
                    onLog("Buttons · btn$buttonId UP · open ${mapping.label}")
                } else {
                    onLog("Buttons · btn$buttonId UP · open ${mapping.label} failed (background launch blocked)")
                }
            }
        }
    }

    private fun startActivity(intent: Intent): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                opts.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                app.startActivity(intent, opts.toBundle())
            } else {
                app.startActivity(intent)
            }
            true
        } catch (e: Exception) {
            onLog("Buttons · startActivity failed: ${e.message}")
            false
        }

    private fun isAppForeground(): Boolean {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pkg = app.packageName
        @Suppress("DEPRECATION")
        val procs = am.runningAppProcesses ?: return false
        return procs.any {
            it.processName == pkg &&
                it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }
}
