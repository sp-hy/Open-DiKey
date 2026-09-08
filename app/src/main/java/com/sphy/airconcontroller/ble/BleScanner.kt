package com.sphy.airconcontroller.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Combined LE scanner + classic inquiry. Accumulates nearby devices for a UI list.
 * Separate from [BleCommandListener], which auto-connects to a named ESP32 target.
 */
class BleScanner(
    private val context: Context,
    private val onDevicesChanged: (List<ScannedBleDevice>) -> Unit,
    private val onStateChanged: (State) -> Unit = {}
) {
    enum class State {
        IDLE,
        SCANNING,
        NO_ADAPTER,
        NO_PERMISSION,
        FAILED
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val devices = ConcurrentHashMap<String, ScannedBleDevice>()
    private var leScanActive = false
    private var classicReceiverRegistered = false
    private var lastErrorCode: Int? = null

    val lastScanErrorCode: Int? get() = lastErrorCode

    private val scanSettings: ScanSettings
        get() {
            val builder = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                builder.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                builder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                builder.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            }
            return builder.build()
        }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            upsertLe(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) upsertLe(result)
            publish()
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "LE onScanFailed error=$errorCode")
            leScanActive = false
            lastErrorCode = errorCode
            // Classic may still be running; only fail hard if classic is also idle.
            if (!isClassicDiscovering()) {
                onStateChanged(State.FAILED)
            }
        }
    }

    private val classicReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        } ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        .toInt()
                    val nameExtra = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    upsertClassic(device, nameExtra, rssi)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.w(TAG, "classic discovery finished, devices=${devices.size}")
                    if (!leScanActive) {
                        onStateChanged(State.IDLE)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun upsertLe(result: ScanResult) {
        val device = result.device
        val address = device.address ?: return
        val record = result.scanRecord
        val name = record.resolvedName(device)
        val connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            result.isConnectable
        } else {
            true
        }
        merge(
            address = address,
            name = name,
            rssi = result.rssi,
            connectable = connectable,
            serviceUuids = record?.serviceUuids.orEmpty(),
            device = device,
            transport = BtTransport.LE
        )
    }

    @SuppressLint("MissingPermission")
    private fun upsertClassic(device: BluetoothDevice, nameExtra: String?, rssi: Int) {
        val address = device.address ?: return
        val name = nameExtra?.takeIf { it.isNotBlank() }
            ?: device.name?.takeIf { it.isNotBlank() }
        merge(
            address = address,
            name = name,
            rssi = if (rssi == Short.MIN_VALUE.toInt()) -127 else rssi,
            connectable = true,
            serviceUuids = emptyList(),
            device = device,
            transport = BtTransport.CLASSIC
        )
    }

    private fun merge(
        address: String,
        name: String?,
        rssi: Int,
        connectable: Boolean,
        serviceUuids: List<android.os.ParcelUuid>,
        device: BluetoothDevice,
        transport: BtTransport
    ) {
        val existing = devices[address]
        val mergedTransport = existing?.withMergedTransport(transport) ?: transport
        val mergedName = name?.takeIf { it.isNotBlank() } ?: existing?.name
        val mergedRssi = when {
            existing == null -> rssi
            rssi > existing.rssi -> rssi
            else -> existing.rssi
        }
        val mergedUuids =
            if (serviceUuids.isNotEmpty()) serviceUuids else existing?.serviceUuids.orEmpty()
        devices[address] = ScannedBleDevice(
            address = address,
            name = mergedName,
            rssi = mergedRssi,
            connectable = connectable || existing?.connectable == true,
            serviceUuids = mergedUuids,
            lastSeenElapsedMs = SystemClock.elapsedRealtime(),
            device = device,
            transport = mergedTransport
        )
        publish()
    }

    private fun publish() {
        onDevicesChanged(sortedSnapshot())
    }

    private fun sortedSnapshot(): List<ScannedBleDevice> =
        devices.values
            .sortedWith(
                compareByDescending<ScannedBleDevice> { it.name != null }
                    .thenByDescending { it.rssi }
                    .thenBy { it.address }
            )

    fun snapshot(): List<ScannedBleDevice> = sortedSnapshot()

    fun clear() {
        devices.clear()
        publish()
    }

    @SuppressLint("MissingPermission")
    fun start(clearFirst: Boolean = true) {
        if (!hasPermission()) {
            onStateChanged(State.NO_PERMISSION)
            return
        }
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            onStateChanged(State.NO_ADAPTER)
            return
        }
        if (clearFirst) {
            devices.clear()
            publish()
        }
        stopScanSafe()
        lastErrorCode = null

        var startedSomething = false

        // LE
        val scanner = bt.bluetoothLeScanner
        if (scanner != null) {
            try {
                // Empty filter list is more reliable than null on some OEM stacks.
                scanner.startScan(emptyList(), scanSettings, scanCallback)
                leScanActive = true
                startedSomething = true
                Log.w(TAG, "LE scan started")
            } catch (e: Exception) {
                Log.w(TAG, "LE startScan failed: ${e.message}")
                leScanActive = false
                lastErrorCode = -1
            }
        } else {
            Log.w(TAG, "bluetoothLeScanner null")
        }

        // Classic inquiry
        try {
            registerClassicReceiver()
            if (bt.isDiscovering) {
                bt.cancelDiscovery()
            }
            val classicOk = bt.startDiscovery()
            Log.w(TAG, "classic startDiscovery=$classicOk")
            if (classicOk) startedSomething = true
        } catch (e: Exception) {
            Log.w(TAG, "classic discovery failed: ${e.message}")
        }

        if (startedSomething) {
            onStateChanged(State.SCANNING)
        } else {
            onStateChanged(State.FAILED)
        }
    }

    fun stop() {
        stopScanSafe()
        onStateChanged(State.IDLE)
    }

    fun isScanning(): Boolean = leScanActive || isClassicDiscovering()

    @SuppressLint("MissingPermission")
    private fun isClassicDiscovering(): Boolean =
        try {
            adapter?.isDiscovering == true
        } catch (_: Exception) {
            false
        }

    @SuppressLint("MissingPermission")
    private fun stopScanSafe() {
        if (leScanActive) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: Exception) {
            }
            leScanActive = false
            Log.w(TAG, "LE scan stopped")
        }
        try {
            if (adapter?.isDiscovering == true) {
                adapter?.cancelDiscovery()
            }
        } catch (_: Exception) {
        }
        unregisterClassicReceiver()
    }

    private fun registerClassicReceiver() {
        if (classicReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(classicReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(classicReceiver, filter)
        }
        classicReceiverRegistered = true
    }

    private fun unregisterClassicReceiver() {
        if (!classicReceiverRegistered) return
        try {
            context.unregisterReceiver(classicReceiver)
        } catch (_: Exception) {
        }
        classicReceiverRegistered = false
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        private const val TAG = "BleScanner"
    }
}
