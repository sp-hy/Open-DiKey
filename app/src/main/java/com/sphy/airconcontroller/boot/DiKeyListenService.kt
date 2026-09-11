package com.sphy.airconcontroller.boot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sphy.airconcontroller.MainActivity
import com.sphy.airconcontroller.OpenDiKeyApp
import com.sphy.airconcontroller.R
import com.sphy.airconcontroller.adb.AdbPermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive after boot so [com.sphy.airconcontroller.dikey.DiKeySession]
 * can talk to the C6 / DiKey without the UI on screen.
 */
class DiKeyListenService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startAsForeground()
        OpenDiKeyApp.from(this).dikey.start()
        CONNECT_RETRY_MS.forEach { delay ->
            main.postDelayed({ OpenDiKeyApp.from(this).dikey.ensureConnected() }, delay)
        }
        main.post {
            com.sphy.airconcontroller.lighting.LightingScheduler.sync(this, forceApply = true)
        }
        scope.launch {
            AdbPermissionManager.ensureVehicleApiAccess(applicationContext)
            if (!AdbPermissionManager.isSetupComplete(applicationContext)) {
                AdbPermissionManager.runSetup(applicationContext)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        OpenDiKeyApp.from(this).dikey.ensureConnected()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val launch = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.listen_notification_title))
            .setContentText(getString(R.string.listen_notification_body))
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.listen_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
            }
        )
    }

    companion object {
        private const val TAG = "DiKeyListen"
        private const val CHANNEL_ID = "dikey_listen"
        private const val NOTIFICATION_ID = 42
        private val CONNECT_RETRY_MS = longArrayOf(2_000L, 8_000L, 20_000L)

        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, DiKeyListenService::class.java)
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService failed: ${e.message}")
                runCatching { app.startService(intent) }
            }
        }
    }
}
