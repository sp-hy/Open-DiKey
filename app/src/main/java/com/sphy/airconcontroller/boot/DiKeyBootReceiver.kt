package com.sphy.airconcontroller.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the DiKey listener after vehicle boot / app update so the key works
 * without opening the UI first.
 */
class DiKeyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in ACTIONS) return
        Log.i(TAG, "start listener for $action")
        DiKeyListenService.start(context)
    }

    companion object {
        private const val TAG = "DiKeyBoot"
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
