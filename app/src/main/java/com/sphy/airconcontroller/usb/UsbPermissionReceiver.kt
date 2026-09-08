package com.sphy.airconcontroller.usb

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.sphy.airconcontroller.DiKeyProbeActivity
import com.sphy.airconcontroller.UsbProbeActivity

/**
 * UsbManager.requestPermission() delivers its result here. Must be a manifest
 * exported receiver: DiLink never shows the system Allow dialog, and a
 * runtime NOT_EXPORTED receiver often never gets the PendingIntent.
 */
class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        Log.w(TAG, "USB permission result granted=$granted")
        val target: Class<out Activity> =
            if (intent.getStringExtra(EXTRA_RETURN) == RETURN_DIKEY) {
                DiKeyProbeActivity::class.java
            } else {
                UsbProbeActivity::class.java
            }
        val launch = Intent(context, target).apply {
            action = ACTION
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtras(intent)
        }
        context.startActivity(launch)
    }

    companion object {
        const val ACTION = "com.sphy.airconcontroller.USB_PERMISSION"
        const val EXTRA_RETURN = "return"
        const val RETURN_DIKEY = "dikey"
        private const val TAG = "UsbProbe"
    }
}
