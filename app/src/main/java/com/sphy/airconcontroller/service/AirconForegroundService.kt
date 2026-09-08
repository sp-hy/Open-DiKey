package com.sphy.airconcontroller.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sphy.airconcontroller.R
import com.sphy.airconcontroller.ble.BleCommandListener
import com.sphy.airconcontroller.ble.BleConnectionRegistry
import com.sphy.airconcontroller.ble.BleConnectionState
import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.byd.SeatLevel
import com.sphy.airconcontroller.byd.SeatMode
import com.sphy.airconcontroller.byd.SeatPosition
import com.sphy.airconcontroller.storage.AppSettings
import kotlin.concurrent.thread

class AirconForegroundService : Service() {
    private var bleListener: BleCommandListener? = null
    private lateinit var ac: BydAcController
    private var lastRequestedTemp: Int? = null
    private var lastRequestedAtMs: Long = 0L
    private var driverHeatLevel: SeatLevel = SeatLevel.OFF
    private var driverCoolLevel: SeatLevel = SeatLevel.OFF
    private var passengerHeatLevel: SeatLevel = SeatLevel.OFF
    private var passengerCoolLevel: SeatLevel = SeatLevel.OFF

    override fun onCreate() {
        super.onCreate()
        ac = BydAcController(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        bleListener?.stop()
        val deviceName = AppSettings(this).espDeviceName
        bleListener = BleCommandListener(
            context = this,
            targetName = deviceName,
            onCommand = ::handleBleCommand,
            onConnectionState = BleConnectionRegistry::setState
        ).also { it.start() }
        return START_STICKY
    }

    private fun handleBleCommand(command: Byte) {
        val b = command.toInt() and 0xff
        when {
            b in 17..33 -> handleTemperatureSetpoint(b)
            b in 0x01..0x04 -> handleSeatButton(b)
            else -> {
                Log.i(
                    "AirconBLE",
                    "ESP32 unknown notify byte=0x${b.toString(16).padStart(2, '0')}"
                )
            }
        }
    }

    private fun handleSeatButton(buttonCode: Int) {
        val (position, mode) = when (buttonCode) {
            0x01 -> SeatPosition.DRIVER to SeatMode.HEAT
            0x02 -> SeatPosition.DRIVER to SeatMode.COOL
            0x03 -> SeatPosition.PASSENGER to SeatMode.HEAT
            0x04 -> SeatPosition.PASSENGER to SeatMode.COOL
            else -> return
        }
        val levelAfter = when (position to mode) {
            SeatPosition.DRIVER to SeatMode.HEAT -> driverHeatLevel.next().also { driverHeatLevel = it }
            SeatPosition.DRIVER to SeatMode.COOL -> driverCoolLevel.next().also { driverCoolLevel = it }
            SeatPosition.PASSENGER to SeatMode.HEAT -> passengerHeatLevel.next().also { passengerHeatLevel = it }
            SeatPosition.PASSENGER to SeatMode.COOL -> passengerCoolLevel.next().also { passengerCoolLevel = it }
            else -> SeatLevel.OFF
        }
        Log.i(
            "AirconBLE",
            "ESP32 seat button ${position.name}/${mode.name} -> $levelAfter (local seat APIs not wired yet)"
        )
    }

    private fun handleTemperatureSetpoint(tempC: Int) {
        val nowMs = System.currentTimeMillis()
        val elapsed = nowMs - lastRequestedAtMs
        if (lastRequestedTemp == tempC && elapsed < 1_000L) return
        if (lastRequestedTemp != null && lastRequestedTemp != tempC && elapsed < 500L) return
        lastRequestedTemp = tempC
        lastRequestedAtMs = nowMs

        val apiTempC = tempC.coerceIn(BydAcController.TEMP_MIN, BydAcController.TEMP_MAX)
        Log.i("AirconBLE", "ESP32 climate setpoint $apiTempC°C")

        thread(name = "local-ac-from-ble") {
            val start = ac.start()
            val temp = ac.setDriverTemp(apiTempC)
            Log.i(
                "AirconBLE",
                "local AC from ESP32 start=${start.detail} temp=${temp.method}->${temp.detail}"
            )
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_body))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        bleListener?.stop()
        bleListener = null
        BleConnectionRegistry.setState(BleConnectionState.IDLE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "byd-aircon-listener"
        private const val NOTIFICATION_ID = 4012
    }
}
