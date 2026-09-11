package com.sphy.airconcontroller

import android.content.pm.PackageManager
import com.sphy.airconcontroller.storage.ButtonAction

fun formatButtonActionSummary(
    packageManager: PackageManager,
    action: ButtonAction,
    openAppFmt: (String) -> String,
    openAppMissingFmt: (String) -> String,
    intentFmt: (String) -> String,
    broadcastFmt: (String) -> String
): String = when (action) {
    is ButtonAction.OpenApp -> {
        val available = packageManager.getLaunchIntentForPackage(action.packageName) != null
        if (available) openAppFmt(action.label) else openAppMissingFmt(action.label)
    }
    is ButtonAction.RunIntent -> {
        if (action.delivery == ButtonAction.RunIntent.DELIVERY_BROADCAST) {
            broadcastFmt(action.label)
        } else {
            intentFmt(action.label)
        }
    }
}
