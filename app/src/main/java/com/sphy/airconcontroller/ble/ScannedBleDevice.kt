package com.sphy.airconcontroller.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid

enum class BtTransport {
    LE,
    CLASSIC,
    BOTH
}

/**
 * One nearby Bluetooth device seen via LE scan and/or classic inquiry.
 * [name] prefers the scan-record / inquiry name when [BluetoothDevice.getName] is null.
 */
data class ScannedBleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val connectable: Boolean,
    val serviceUuids: List<ParcelUuid>,
    val lastSeenElapsedMs: Long,
    val device: BluetoothDevice,
    val transport: BtTransport
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: "Unknown"

    fun withMergedTransport(other: BtTransport): BtTransport =
        when {
            transport == other -> transport
            transport == BtTransport.BOTH || other == BtTransport.BOTH -> BtTransport.BOTH
            else -> BtTransport.BOTH
        }
}

fun ScanRecord?.resolvedName(device: BluetoothDevice): String? =
    this?.deviceName?.takeIf { it.isNotBlank() } ?: device.name?.takeIf { it.isNotBlank() }
