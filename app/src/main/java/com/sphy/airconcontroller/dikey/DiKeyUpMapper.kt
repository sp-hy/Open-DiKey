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
import com.sphy.airconcontroller.storage.ButtonAction
import com.sphy.airconcontroller.storage.toAmCommand
import com.sphy.airconcontroller.storage.toAndroidIntent
import java.util.concurrent.Executors

/**
 * Remapped button / dial slots → open app or run Android Intent.
 * Climate defaults stay in [DiKeyClimateMapper] when a press has no mapping.
 */
class DiKeyUpMapper(
    private val app: Context,
    private val settings: AppSettings,
    private val onLog: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    fun handle(event: DiKeyEvent) {
        when (event) {
            is DiKeyEvent.Button -> {
                val slot = ButtonMapSlot.from(event.direction, event.event) ?: return
                if (!slot.remappable) return
                val mapping = settings.buttonAction(event.logicalId, slot) ?: return
                main.post {
                    dispatch("btn${event.logicalId} ${slotLabel(slot)}", mapping)
                }
            }
            is DiKeyEvent.Encoder -> {
                val slot = DialMapSlot.from(event.side, event.event) ?: return
                if (!slot.remappable) return
                val mapping = settings.dialAction(slot) ?: return
                main.post {
                    dispatch("${event.side.lowercase()} dial ${dialSlotLabel(slot)}", mapping)
                }
            }
            is DiKeyEvent.Raw -> Unit
        }
    }

    private fun dispatch(tag: String, mapping: ButtonAction) {
        when (mapping) {
            is ButtonAction.OpenApp -> launchApp(tag, mapping)
            is ButtonAction.RunIntent -> runIntent(tag, mapping)
        }
    }

    private fun launchApp(tag: String, mapping: ButtonAction.OpenApp) {
        val intent = app.packageManager.getLaunchIntentForPackage(mapping.packageName)
        if (intent == null) {
            onLog("Map · $tag · ${mapping.label} is not installed")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val foreground = isAppForeground()
        if (foreground) {
            if (startActivity(intent)) {
                onLog("Map · $tag · open ${mapping.label}")
                return
            }
        }
        val component = intent.component?.flattenToShortString()
        io.execute {
            val viaAdb = AdbPermissionManager.launchComponent(app, mapping.packageName, component)
            main.post {
                if (viaAdb) {
                    onLog("Map · $tag · open ${mapping.label}")
                } else if (!foreground && startActivity(intent)) {
                    onLog("Map · $tag · open ${mapping.label}")
                } else {
                    onLog("Map · $tag · open ${mapping.label} failed (background launch blocked)")
                }
            }
        }
    }

    private fun runIntent(tag: String, mapping: ButtonAction.RunIntent) {
        val intent = mapping.toAndroidIntent()
        if (mapping.delivery == ButtonAction.RunIntent.DELIVERY_BROADCAST) {
            try {
                app.sendBroadcast(intent)
                onLog("Map · $tag · broadcast ${mapping.label}")
            } catch (e: Exception) {
                io.execute {
                    val viaAdb = AdbPermissionManager.runAmCommand(app, mapping.toAmCommand())
                    main.post {
                        if (viaAdb) {
                            onLog("Map · $tag · broadcast ${mapping.label} (adb)")
                        } else {
                            onLog("Map · $tag · broadcast failed: ${e.message}")
                        }
                    }
                }
            }
            return
        }

        val foreground = isAppForeground()
        if (foreground && startActivity(intent)) {
            onLog("Map · $tag · intent ${mapping.label}")
            return
        }
        io.execute {
            val viaAdb = AdbPermissionManager.runAmCommand(app, mapping.toAmCommand())
            main.post {
                if (viaAdb) {
                    onLog("Map · $tag · intent ${mapping.label}")
                } else if (!foreground && startActivity(intent)) {
                    onLog("Map · $tag · intent ${mapping.label}")
                } else {
                    onLog("Map · $tag · intent ${mapping.label} failed")
                }
            }
        }
    }

    private fun slotLabel(slot: ButtonMapSlot): String =
        "${slot.direction} ${if (slot.event == "LONG_PRESS") "LONG" else "CLICK"}"

    private fun dialSlotLabel(slot: DialMapSlot): String =
        if (slot.event == "LONG_PRESS") "long" else "click"

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
            onLog("Map · startActivity failed: ${e.message}")
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
