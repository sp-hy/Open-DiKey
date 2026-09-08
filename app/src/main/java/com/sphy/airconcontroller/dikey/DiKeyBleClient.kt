package com.sphy.airconcontroller.dikey

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Phone-side GATT transport. DiLink uses [com.sphy.airconcontroller.usb.DiKeyUsbBridge] instead. */
class DiKeyBleClient(
    private val context: Context,
    private val controller: DiKeyController
) : DiKeyFrameSink {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    private val writeTimeoutRunnable = Runnable {
        controller.onWriteComplete(success = false)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        close()
        controller.attachSink(this)
        controller.postStatus("Connecting to ${device.address}…")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        close()
        controller.onTransportDown("Disconnected")
    }

    @SuppressLint("MissingPermission")
    fun close() {
        mainHandler.removeCallbacks(writeTimeoutRunnable)
        writeChar = null
        runCatching { gatt?.close() }
        gatt = null
    }

    @SuppressLint("MissingPermission")
    override fun writeFrame(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = writeChar ?: return false
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, bytes, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = writeType
                ch.value = bytes
                g.writeCharacteristic(ch)
            }
        }
        if (ok) {
            mainHandler.removeCallbacks(writeTimeoutRunnable)
            mainHandler.postDelayed(writeTimeoutRunnable, WRITE_TIMEOUT_MS)
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                mainHandler.postDelayed({
                    mainHandler.removeCallbacks(writeTimeoutRunnable)
                    controller.onWriteComplete(success = true)
                }, NO_RESPONSE_GAP_MS)
            }
        }
        return ok
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post {
                    controller.onTransportDown("Connection error $status")
                    close()
                }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    mainHandler.post { controller.postStatus("Connected — discovering services…") }
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    mainHandler.post {
                        writeChar = null
                        controller.onTransportDown("Disconnected")
                        close()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post { controller.onTransportDown("Service discovery failed ($status)") }
                return
            }
            val service = g.getService(UUID.fromString(DiKeyProtocol.SERVICE_UUID))
            if (service == null) {
                mainHandler.post { controller.onTransportDown("FF10 service not found (not a DiKey?)") }
                return
            }
            val write = service.getCharacteristic(UUID.fromString(DiKeyProtocol.WRITE_UUID))
            val notify = service.getCharacteristic(UUID.fromString(DiKeyProtocol.NOTIFY_UUID))
            if (write == null || notify == null) {
                mainHandler.post { controller.onTransportDown("FF11/FF12 characteristics missing") }
                return
            }
            writeChar = write
            writeType = resolveWriteType(write)
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(UUID.fromString(DiKeyProtocol.CCCD_UUID))
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        g.writeDescriptor(cccd)
                    }
                }
            } else {
                mainHandler.post { controller.onTransportReady() }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            mainHandler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    controller.onTransportReady()
                } else {
                    controller.onTransportDown("Notify enable failed ($status)")
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            mainHandler.post { controller.ingestNotify(value) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            mainHandler.post { controller.ingestNotify(value) }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) return
            mainHandler.removeCallbacks(writeTimeoutRunnable)
            mainHandler.post { controller.onWriteComplete(status == BluetoothGatt.GATT_SUCCESS) }
        }
    }

    private fun resolveWriteType(ch: BluetoothGattCharacteristic): Int {
        val props = ch.properties
        return when {
            props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 2500L
        private const val NO_RESPONSE_GAP_MS = 40L
    }
}
