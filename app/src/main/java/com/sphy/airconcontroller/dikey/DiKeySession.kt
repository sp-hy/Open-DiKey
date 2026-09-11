package com.sphy.airconcontroller.dikey

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.sphy.airconcontroller.ble.BleScanner
import com.sphy.airconcontroller.ble.BtTransport
import com.sphy.airconcontroller.ble.ScannedBleDevice
import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.usb.DiKeyUsbBridge
import com.sphy.airconcontroller.usb.UsbHostSerial
import com.sphy.airconcontroller.usb.UsbPermissionReceiver
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide DiKey connection: USB C6 bridge first, BLE fallback.
 * Climate mapping and LED restore run here so the key works from app launch,
 * not only while a probe screen is open.
 */
class DiKeySession(private val app: Context) {
    val settings = AppSettings(app)
    val usbManager: UsbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow("Starting DiKey…")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _events = MutableSharedFlow<DiKeyEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DiKeyEvent> = _events.asSharedFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val _bleDevices = MutableStateFlow<List<ScannedBleDevice>>(emptyList())
    val bleDevices: StateFlow<List<ScannedBleDevice>> = _bleDevices.asStateFlow()

    private val _bleScanState = MutableStateFlow(BleScanner.State.IDLE)
    val bleScanState: StateFlow<BleScanner.State> = _bleScanState.asStateFlow()

    val controller = DiKeyController(
        onStatus = { msg ->
            _status.value = msg
            if (shouldLogStatus(msg)) emitLog(msg)
            when {
                msg.startsWith("Ready") -> autoConnectInFlight = false
                msg.startsWith("Disconnected") ||
                    msg.startsWith("Connection error") ||
                    msg.startsWith("STATE DISCONNECTED") ||
                    msg.contains("not found") ||
                    msg.contains("missing") -> {
                    autoConnectInFlight = false
                    if (!usingUsbBridge) {
                        autoConnectArmed = true
                        mainHandler.postDelayed({ startAutoScan() }, RECONNECT_SCAN_DELAY_MS)
                    }
                }
            }
        },
        onEvent = { event ->
            persistDialFromEvent(event)
            _events.tryEmit(event)
            climateMapper.handle(event)
            upMapper.handle(event)
        }
    )

    private val climateMapper: DiKeyClimateMapper
    private val upMapper: DiKeyUpMapper

    val bleClient: DiKeyBleClient
    val usbBridge: DiKeyUsbBridge
    val bleScanner: BleScanner

    init {
        climateMapper = DiKeyClimateMapper(
            ac = BydAcController(app),
            dikey = controller,
            onLog = { emitLog(it) }
        )
        upMapper = DiKeyUpMapper(
            app = app,
            settings = settings,
            onLog = { emitLog(it) }
        )
        bleClient = DiKeyBleClient(context = app, controller = controller)
        usbBridge = DiKeyUsbBridge(usbManager, controller)
        bleScanner = BleScanner(
            context = app,
            onDevicesChanged = { devices ->
                mainHandler.post {
                    _bleDevices.value = devices
                    maybeAutoConnect(devices)
                }
            },
            onStateChanged = { state ->
                mainHandler.post { _bleScanState.value = state }
            }
        )
    }

    @Volatile
    var selectedBle: ScannedBleDevice? = null
        private set

    private var started = false
    private var autoConnectArmed = true
    private var autoConnectInFlight = false
    private var usingUsbBridge = false
    private var waitingUsbPermission = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    emitLog("USB attached")
                    tryConnectUsb(deviceExtra(intent))
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    emitLog("USB detached")
                    if (usingUsbBridge) {
                        usingUsbBridge = false
                        usbBridge.close()
                        controller.onTransportDown("USB detached")
                        autoConnectArmed = true
                        startAutoScan()
                    }
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        seedFromSettings()
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(usbReceiver, usbFilter)
        }
        ensureConnected()
    }

    fun ensureConnected() {
        if (controller.isConnected || autoConnectInFlight || usbBridge.isUsbOpen) return
        if (tryConnectUsb(null)) return
        autoConnectArmed = true
        startAutoScan()
    }

    fun onUsbPermissionResult(intent: Intent) {
        waitingUsbPermission = false
        val device = deviceExtra(intent) ?: return
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        emitLog("USB permission granted=$granted")
        openUsbBridge(device)
    }

    fun tryConnectUsb(preferred: UsbDevice?): Boolean {
        val device = preferred
            ?: usbManager.deviceList.values.firstOrNull { UsbHostSerial.isLikelyProbe(it) }
            ?: return false
        if (usbBridge.isUsbOpen) {
            usingUsbBridge = true
            return true
        }
        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(device)
            return true
        }
        return openUsbBridge(device)
    }

    fun selectBle(device: ScannedBleDevice) {
        selectedBle = device
        settings.dikeyLastAddress = device.address
    }

    fun connectSelected(): Boolean {
        if (tryConnectUsb(null)) return true
        val device = selectedBle ?: return false
        connectBle(device)
        return true
    }

    fun connectBle(device: ScannedBleDevice) {
        selectBle(device)
        autoConnectArmed = false
        autoConnectInFlight = true
        usingUsbBridge = false
        bleScanner.stop()
        seedFromSettings()
        _status.value = "Connecting to ${device.displayName}…"
        emitLog("BLE connect → ${device.displayName} · ${device.address}")
        bleClient.connect(device.device)
    }

    fun startAutoScan(clearFirst: Boolean = false) {
        if (controller.isConnected || autoConnectInFlight || usingUsbBridge || usbBridge.isUsbOpen) {
            return
        }
        if (!hasBluetoothPermissions()) {
            _status.value = "Bluetooth permission needed for DiKey fallback scan"
            _bleScanState.value = BleScanner.State.NO_PERMISSION
            return
        }
        _status.value = "Looking for DiKey…"
        bleScanner.start(clearFirst = clearFirst)
    }

    fun stopBleScan() {
        autoConnectArmed = false
        bleScanner.stop()
    }

    fun disconnect(rescan: Boolean = true) {
        autoConnectArmed = rescan
        autoConnectInFlight = false
        usingUsbBridge = false
        waitingUsbPermission = false
        bleClient.disconnect()
        usbBridge.close()
        controller.onTransportDown("Disconnected")
        if (rescan) startAutoScan(clearFirst = true)
    }

    fun applyBarColor(bar: Int, red: Int, green: Int, blue: Int, mode: Int? = null): Boolean {
        val m = mode ?: settings.modeForBar(bar)
        settings.updateEditingBar(bar, m, red, green, blue)
        if (settings.editingLightingPeriod != settings.liveLightingPeriod()) {
            return true
        }
        controller.seedLedMemory(settings.ledRestoreSnapshot())
        if (!controller.isConnected) return false
        return controller.sendLed(m, bar, red, green, blue)
    }

    fun applyBacklight(red: Int, green: Int, blue: Int): Boolean {
        settings.updateEditingBacklight(red, green, blue)
        if (settings.editingLightingPeriod != settings.liveLightingPeriod()) {
            return true
        }
        controller.seedLedMemory(settings.ledRestoreSnapshot())
        if (!controller.isConnected) return false
        return controller.sendButtonBacklight(red, green, blue)
    }

    fun applyLightingPeriod(period: com.sphy.airconcontroller.lighting.LightingPeriod): Boolean {
        settings.applyProfileToLive(period)
        val snapshot = settings.ledRestoreSnapshot()
        controller.seedLedMemory(snapshot)
        if (!controller.isConnected) return false
        var ok = true
        for ((bar, state) in snapshot.bars) {
            ok = controller.sendLed(
                state.mode,
                bar,
                state.color.red,
                state.color.green,
                state.color.blue
            ) && ok
        }
        snapshot.backlight?.let { bl ->
            ok = controller.sendButtonBacklight(bl.red, bl.green, bl.blue) && ok
        }
        return ok
    }

    fun seedFromSettings() {
        val leftType = settings.leftDialType()
        val rightType = settings.rightDialType()
        val leftValue = leftType.clamp(settings.dikeyLeftValue)
        val rightValue = rightType.clamp(settings.dikeyRightValue)
        controller.seedDialMemory(
            leftType = leftType.code,
            leftValue = leftValue,
            rightType = rightType.code,
            rightValue = rightValue
        )
        controller.seedLedMemory(settings.ledRestoreSnapshot())
    }

    fun hasBluetoothPermissions(): Boolean =
        requiredBluetoothPermissions().all {
            ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
        }

    fun requiredBluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

    val isConnected: Boolean get() = controller.isConnected
    val isUsbOpen: Boolean get() = usbBridge.isUsbOpen

    private fun openUsbBridge(device: UsbDevice): Boolean {
        bleScanner.stop()
        usingUsbBridge = true
        autoConnectInFlight = true
        seedFromSettings()
        val err = usbBridge.open(device)
        if (err != null) {
            usingUsbBridge = false
            autoConnectInFlight = false
            emitLog(err)
            _status.value = err
            return false
        }
        _status.value = "USB bridge open — waiting for DiKey…"
        emitLog("USB bridge ${UsbHostSerial.label(device)}")
        return true
    }

    private fun requestUsbPermission(device: UsbDevice) {
        waitingUsbPermission = true
        usingUsbBridge = true
        val intent = Intent(app, UsbPermissionReceiver::class.java).apply {
            action = UsbPermissionReceiver.ACTION
            putExtra(UsbPermissionReceiver.EXTRA_RETURN, UsbPermissionReceiver.RETURN_DIKEY)
        }
        val pi = PendingIntent.getBroadcast(
            app,
            1,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        emitLog("Requesting USB permission…")
        _status.value = "Waiting for USB permission. If no popup appears, unplug and replug the USB bridge."
        usbManager.requestPermission(device, pi)
        mainHandler.postDelayed({
            if (!waitingUsbPermission || usbBridge.isUsbOpen) return@postDelayed
            openUsbBridge(device)
        }, 2500)
    }

    private fun maybeAutoConnect(devices: List<ScannedBleDevice>) {
        if (!autoConnectArmed || autoConnectInFlight || controller.isConnected || usingUsbBridge) {
            return
        }
        val target = findDiKeyTarget(devices) ?: return
        autoConnectArmed = false
        emitLog("Auto-connect → ${target.displayName} · ${target.address}")
        connectBle(target)
    }

    private fun persistDialFromEvent(event: DiKeyEvent) {
        if (event !is DiKeyEvent.Encoder) return
        val type = DialDisplayType.fromCode(event.displayType) ?: return
        settings.saveDial(event.side == "LEFT", type, event.value)
    }

    private fun emitLog(line: String) {
        _logs.tryEmit(line)
    }

    private fun shouldLogStatus(msg: String): Boolean =
        msg.startsWith("TX ·") ||
            msg.startsWith("Write") ||
            msg.startsWith("Restoring") ||
            msg.startsWith("STATE") ||
            msg.startsWith("LOG") ||
            msg.startsWith("USB") ||
            msg.startsWith("HB")

    private fun deviceExtra(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        private const val DIKEY_DEVICE_NAME = "DiKey"
        private const val RECONNECT_SCAN_DELAY_MS = 1500L

        fun findDiKeyTarget(devices: List<ScannedBleDevice>): ScannedBleDevice? {
            fun nameMatches(device: ScannedBleDevice): Boolean {
                val n = device.name?.trim() ?: return false
                return n.equals(DIKEY_DEVICE_NAME, ignoreCase = true) ||
                    n.contains(DIKEY_DEVICE_NAME, ignoreCase = true)
            }
            val matches = devices.filter(::nameMatches)
            if (matches.isEmpty()) return null
            val exact = matches.filter {
                it.name?.trim().equals(DIKEY_DEVICE_NAME, ignoreCase = true) == true
            }
            val pool = exact.ifEmpty { matches }
            return pool
                .sortedWith(
                    compareByDescending<ScannedBleDevice> { it.connectable }
                        .thenByDescending {
                            it.transport == BtTransport.LE || it.transport == BtTransport.BOTH
                        }
                        .thenByDescending { it.rssi }
                )
                .firstOrNull()
        }
    }
}
