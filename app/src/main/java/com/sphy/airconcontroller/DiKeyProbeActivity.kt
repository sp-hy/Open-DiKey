package com.sphy.airconcontroller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.sphy.airconcontroller.ble.BleDeviceListAdapter
import com.sphy.airconcontroller.ble.BleScanner
import com.sphy.airconcontroller.ble.BtTransport
import com.sphy.airconcontroller.ble.ScannedBleDevice
import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.dikey.DiKeyBleClient
import com.sphy.airconcontroller.dikey.DiKeyClimateMapper
import com.sphy.airconcontroller.dikey.DiKeyController
import com.sphy.airconcontroller.dikey.DiKeyEvent
import com.sphy.airconcontroller.dikey.DialDisplayType
import com.sphy.airconcontroller.dikey.LedMode
import com.sphy.airconcontroller.dikey.LedPosition
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.usb.DiKeyUsbBridge
import com.sphy.airconcontroller.usb.UsbHostSerial
import com.sphy.airconcontroller.usb.UsbPermissionReceiver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Scan/connect DiKey, log button/dial events, push dial LCD + LED. */
class DiKeyProbeActivity : AppCompatActivity() {
    private lateinit var settings: AppSettings
    private lateinit var connStatusText: TextView
    private lateinit var bleScanStatusText: TextView
    private lateinit var bleSelectedDeviceText: TextView
    private lateinit var eventLogText: TextView
    private lateinit var ledPreview: View
    private lateinit var redLabel: TextView
    private lateinit var greenLabel: TextView
    private lateinit var blueLabel: TextView
    private lateinit var dialValueLabel: TextView
    private lateinit var redSeek: SeekBar
    private lateinit var greenSeek: SeekBar
    private lateinit var blueSeek: SeekBar
    private lateinit var dialValueSeek: SeekBar
    private lateinit var modeSpinner: Spinner
    private lateinit var positionSpinner: Spinner
    private lateinit var dialSideSpinner: Spinner
    private lateinit var dialTypeSpinner: Spinner
    private lateinit var bleDeviceAdapter: BleDeviceListAdapter
    private lateinit var bleScanner: BleScanner
    private lateinit var controller: DiKeyController
    private lateinit var climateMapper: DiKeyClimateMapper
    private lateinit var bleClient: DiKeyBleClient
    private lateinit var usbBridge: DiKeyUsbBridge
    private lateinit var usbManager: UsbManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val eventLines = ArrayDeque<String>(MAX_LOG_LINES)
    private var latestScanCount = 0
    private var selected: ScannedBleDevice? = null
    /** When true, first matching "DiKey" advertisement triggers connect. */
    private var autoConnectArmed = true
    private var autoConnectInFlight = false
    /** Prefer C6 USB bridge on DiLink; BLE scan is fallback. */
    private var usingUsbBridge = false
    private var waitingUsbPermission = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLogLine("USB attached")
                    tryConnectUsb(deviceExtra(intent))
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLogLine("USB detached")
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
    /** Suppress spinner listeners while restoring UI from prefs. */
    private var suppressUiCallbacks = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dikey_probe)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.dikey_title)

        settings = AppSettings(this)
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        connStatusText = findViewById(R.id.dikeyConnStatusText)
        bleScanStatusText = findViewById(R.id.bleScanStatusText)
        bleSelectedDeviceText = findViewById(R.id.bleSelectedDeviceText)
        eventLogText = findViewById(R.id.dikeyEventLog)
        ledPreview = findViewById(R.id.dikeyLedPreview)
        redLabel = findViewById(R.id.dikeyLedRedLabel)
        greenLabel = findViewById(R.id.dikeyLedGreenLabel)
        blueLabel = findViewById(R.id.dikeyLedBlueLabel)
        dialValueLabel = findViewById(R.id.dikeyDialValueLabel)
        redSeek = findViewById(R.id.dikeyLedRedSeek)
        greenSeek = findViewById(R.id.dikeyLedGreenSeek)
        blueSeek = findViewById(R.id.dikeyLedBlueSeek)
        dialValueSeek = findViewById(R.id.dikeyDialValueSeek)
        modeSpinner = findViewById(R.id.dikeyLedModeSpinner)
        positionSpinner = findViewById(R.id.dikeyLedPositionSpinner)
        dialSideSpinner = findViewById(R.id.dikeyDialSideSpinner)
        dialTypeSpinner = findViewById(R.id.dikeyDialTypeSpinner)

        bleDeviceAdapter = BleDeviceListAdapter(this)
        findViewById<ListView>(R.id.bleDeviceList).apply {
            adapter = bleDeviceAdapter
            setOnItemClickListener { _, _, position, _ ->
                autoConnectArmed = false
                onDeviceSelected(bleDeviceAdapter.getItem(position))
            }
        }

        bleScanner = BleScanner(
            context = this,
            onDevicesChanged = { devices ->
                mainHandler.post {
                    latestScanCount = devices.size
                    bleDeviceAdapter.submit(devices)
                    if (bleScanner.isScanning()) {
                        bleScanStatusText.text =
                            getString(R.string.ble_scan_scanning, devices.size)
                    }
                    maybeAutoConnect(devices)
                }
            },
            onStateChanged = { state ->
                mainHandler.post { renderBleScanState(state) }
            }
        )

        controller = DiKeyController(
            onStatus = { msg ->
                connStatusText.text = msg
                if (msg.startsWith("TX ·") || msg.startsWith("Write") ||
                    msg.startsWith("Restoring") || msg.startsWith("STATE") ||
                    msg.startsWith("LOG") || msg.startsWith("USB") || msg.startsWith("HB")
                ) {
                    appendLogLine(msg)
                }
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
                appendEvent(event)
                climateMapper.handle(event)
            }
        )
        climateMapper = DiKeyClimateMapper(
            ac = BydAcController(this),
            dikey = controller,
            onLog = { appendLogLine(it) }
        )
        bleClient = DiKeyBleClient(context = this, controller = controller)
        usbBridge = DiKeyUsbBridge(usbManager, controller)

        modeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            LedMode.entries.map { it.label }
        )

        positionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            LedPosition.entries.map { it.label }
        )

        dialSideSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.dikey_dial_side_left),
                getString(R.string.dikey_dial_side_right)
            )
        )

        dialTypeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            DialDisplayType.entries.map { it.label }
        )

        dialSideSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (suppressUiCallbacks) return
                settings.dikeyUiSideLeft = position == 0
                loadDialUiForSelectedSide()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        dialTypeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (suppressUiCallbacks) return
                val type = DialDisplayType.entries[position]
                val left = dialSideSpinner.selectedItemPosition == 0
                val existing = if (left) {
                    if (settings.dikeyLeftTypeCode == type.code) settings.dikeyLeftValue else type.defaultValue
                } else {
                    if (settings.dikeyRightTypeCode == type.code) settings.dikeyRightValue else type.defaultValue
                }
                applyDialValueRange(type, existing)
                settings.saveDial(left, type, type.min + dialValueSeek.progress)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        val colorListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateColorLabels()
                if (fromUser) persistLedFromUi()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        redSeek.setOnSeekBarChangeListener(colorListener)
        greenSeek.setOnSeekBarChangeListener(colorListener)
        blueSeek.setOnSeekBarChangeListener(colorListener)

        dialValueSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val type = DialDisplayType.entries[dialTypeSpinner.selectedItemPosition]
                val value = type.min + progress
                dialValueLabel.text = getString(R.string.dikey_dial_value_fmt, value)
                if (fromUser && !suppressUiCallbacks) {
                    settings.saveDial(dialSideSpinner.selectedItemPosition == 0, type, value)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        restoreUiFromSettings()

        findViewById<Button>(R.id.startBleScanButton).setOnClickListener {
            autoConnectArmed = true
            startAutoScan(clearFirst = true)
        }
        findViewById<Button>(R.id.stopBleScanButton).setOnClickListener {
            autoConnectArmed = false
            bleScanner.stop()
        }
        findViewById<Button>(R.id.dikeyConnectButton).setOnClickListener {
            autoConnectArmed = false
            connectSelected()
        }
        findViewById<Button>(R.id.dikeyDisconnectButton).setOnClickListener {
            autoConnectArmed = true
            autoConnectInFlight = false
            usingUsbBridge = false
            bleClient.disconnect()
            usbBridge.close()
            controller.onTransportDown("Disconnected")
            startAutoScan(clearFirst = true)
        }
        findViewById<Button>(R.id.dikeyClearLogButton).setOnClickListener {
            eventLines.clear()
            eventLogText.text = getString(R.string.dikey_event_log_empty)
        }
        findViewById<Button>(R.id.dikeyApplyDialButton).setOnClickListener { applyDial() }
        findViewById<Button>(R.id.dikeyApplyLedButton).setOnClickListener { applyLed(all = false) }
        findViewById<Button>(R.id.dikeyApplyLedAllButton).setOnClickListener { applyLed(all = true) }
        findViewById<Button>(R.id.dikeyApplyBacklightButton).setOnClickListener { applyBacklight() }

        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, usbFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, usbFilter)
        }
        handleUsbLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (controller.isConnected || autoConnectInFlight || usbBridge.isUsbOpen) return
        if (tryConnectUsb(null)) return
        autoConnectArmed = true
        startAutoScan()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbLaunchIntent(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStop() {
        mainHandler.removeCallbacksAndMessages(null)
        bleScanner.stop()
        super.onStop()
    }

    override fun onDestroy() {
        waitingUsbPermission = false
        unregisterReceiver(usbReceiver)
        usbBridge.close()
        bleClient.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startAutoScan(clearFirst = true)
        }
    }

    private fun handleUsbLaunchIntent(intent: Intent?) {
        if (intent?.action != UsbPermissionReceiver.ACTION) return
        waitingUsbPermission = false
        val device = deviceExtra(intent) ?: return
        appendLogLine(
            "USB permission granted=${intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)}"
        )
        openUsbBridge(device)
    }

    private fun tryConnectUsb(preferred: UsbDevice?): Boolean {
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

    private fun openUsbBridge(device: UsbDevice): Boolean {
        bleScanner.stop()
        usingUsbBridge = true
        autoConnectInFlight = true
        seedClientFromSettings()
        val err = usbBridge.open(device)
        if (err != null) {
            usingUsbBridge = false
            autoConnectInFlight = false
            appendLogLine(err)
            connStatusText.text = err
            return false
        }
        connStatusText.text = getString(R.string.dikey_usb_waiting)
        appendLogLine("USB bridge ${UsbHostSerial.label(device)}")
        return true
    }

    private fun requestUsbPermission(device: UsbDevice) {
        waitingUsbPermission = true
        usingUsbBridge = true
        val intent = Intent(this, UsbPermissionReceiver::class.java).apply {
            action = UsbPermissionReceiver.ACTION
            putExtra(UsbPermissionReceiver.EXTRA_RETURN, UsbPermissionReceiver.RETURN_DIKEY)
        }
        val pi = PendingIntent.getBroadcast(
            this,
            1,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        appendLogLine("Requesting USB permission…")
        connStatusText.text = getString(R.string.usb_perm_waiting)
        usbManager.requestPermission(device, pi)
        mainHandler.postDelayed({
            if (!waitingUsbPermission || usbBridge.isUsbOpen || isDestroyed) return@postDelayed
            openUsbBridge(device)
        }, 2500)
    }

    private fun deviceExtra(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun startAutoScan(clearFirst: Boolean = false) {
        if (controller.isConnected || autoConnectInFlight || usingUsbBridge || usbBridge.isUsbOpen) return
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            connStatusText.text = getString(R.string.ble_scan_no_permission)
            return
        }
        connStatusText.text = getString(R.string.dikey_auto_scanning)
        bleScanner.start(clearFirst = clearFirst)
    }

    private fun maybeAutoConnect(devices: List<ScannedBleDevice>) {
        if (!autoConnectArmed || autoConnectInFlight || controller.isConnected || usingUsbBridge) return
        val target = findDiKeyTarget(devices) ?: return
        autoConnectArmed = false
        autoConnectInFlight = true
        onDeviceSelected(target)
        connStatusText.text =
            getString(R.string.dikey_auto_connecting, target.displayName)
        appendLogLine("Auto-connect → ${target.displayName} · ${target.address}")
        bleScanner.stop()
        seedClientFromSettings()
        bleClient.connect(target.device)
    }

    /** Prefer exact "DiKey", then name containing it; strongest connectable LE first. */
    private fun findDiKeyTarget(devices: List<ScannedBleDevice>): ScannedBleDevice? {
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

    private fun onDeviceSelected(device: ScannedBleDevice) {
        selected = device
        settings.dikeyLastAddress = device.address
        bleDeviceAdapter.select(device.address)
        bleSelectedDeviceText.text =
            getString(R.string.ble_scan_selected, device.displayName, device.address)
    }

    @SuppressLint("MissingPermission")
    private fun connectSelected() {
        if (tryConnectUsb(null)) return
        val device = selected
        if (device == null) {
            Snackbar.make(connStatusText, R.string.dikey_select_first, Snackbar.LENGTH_SHORT).show()
            return
        }
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            Snackbar.make(connStatusText, R.string.missing_permissions, Snackbar.LENGTH_SHORT).show()
            return
        }
        autoConnectInFlight = true
        bleScanner.stop()
        seedClientFromSettings()
        bleClient.connect(device.device)
    }

    private fun seedClientFromSettings() {
        val leftType = settings.leftDialType()
        val rightType = settings.rightDialType()
        val leftValue = leftType.clamp(settings.dikeyLeftValue)
        val rightValue = rightType.clamp(settings.dikeyRightValue)
        appendLogLine(
            "Prefs · L ${leftType.label}=$leftValue · R ${rightType.label}=$rightValue"
        )
        controller.seedDialMemory(
            leftType = leftType.code,
            leftValue = leftValue,
            rightType = rightType.code,
            rightValue = rightValue
        )
        val snap = settings.ledRestoreSnapshot()
        controller.seedLedMemory(snap)
        val barsLog = if (snap.bars.isEmpty()) {
            "(none)"
        } else {
            snap.bars.entries.sortedBy { it.key }.joinToString(" · ") { (bar, s) ->
                "$bar=(${s.color.red},${s.color.green},${s.color.blue})"
            }
        }
        val keysLog = snap.backlight?.let { "(${it.red},${it.green},${it.blue})" } ?: "(unset)"
        appendLogLine("Prefs · LED bars $barsLog · keys=$keysLog")
    }

    private fun requireConnected(): Boolean {
        if (controller.isConnected) return true
        Snackbar.make(connStatusText, R.string.dikey_not_connected, Snackbar.LENGTH_SHORT).show()
        return false
    }

    private fun applyDial() {
        if (!requireConnected()) return
        val left = dialSideSpinner.selectedItemPosition == 0
        val type = DialDisplayType.entries[dialTypeSpinner.selectedItemPosition]
        val value = type.min + dialValueSeek.progress
        settings.saveDial(left, type, value)
        settings.dikeyUiSideLeft = left
        val ok = controller.sendDialDisplay(left, type.code, value)
        Snackbar.make(
            connStatusText,
            if (ok) R.string.dikey_dial_sent else R.string.dikey_led_send_failed,
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun restoreUiFromSettings() {
        suppressUiCallbacks = true
        try {
            dialSideSpinner.setSelection(if (settings.dikeyUiSideLeft) 0 else 1)
            loadDialUiForSelectedSide()

            val modeOrd = LedMode.entries.indexOfFirst { it.code == settings.dikeyLedModeCode }
                .takeIf { it >= 0 } ?: LedMode.ON.ordinal
            modeSpinner.setSelection(modeOrd)
            positionSpinner.setSelection(
                LedPosition.entries.indexOfFirst { it.code == settings.dikeyLedPosition }
                    .takeIf { it >= 0 } ?: 0
            )
            redSeek.progress = settings.dikeyLedRed
            greenSeek.progress = settings.dikeyLedGreen
            blueSeek.progress = settings.dikeyLedBlue
            updateColorLabels()
        } finally {
            suppressUiCallbacks = false
        }
    }

    private fun loadDialUiForSelectedSide() {
        val left = dialSideSpinner.selectedItemPosition == 0
        val type = if (left) settings.leftDialType() else settings.rightDialType()
        val value = if (left) settings.dikeyLeftValue else settings.dikeyRightValue
        suppressUiCallbacks = true
        try {
            dialTypeSpinner.setSelection(type.ordinal)
            applyDialValueRange(type, value)
        } finally {
            suppressUiCallbacks = false
        }
    }

    private fun applyDialValueRange(type: DialDisplayType, preferredValue: Int = type.defaultValue) {
        val span = (type.max - type.min).coerceAtLeast(0)
        dialValueSeek.max = span
        dialValueSeek.progress = (type.clamp(preferredValue) - type.min).coerceIn(0, span)
        dialValueLabel.text = getString(R.string.dikey_dial_value_fmt, type.min + dialValueSeek.progress)
    }

    private fun persistLedFromUi() {
        // UI-only last selection; strip/backlight maps updated on Apply.
        val mode = LedMode.entries[modeSpinner.selectedItemPosition].code
        settings.saveLedUi(
            modeCode = mode,
            position = LedPosition.entries[positionSpinner.selectedItemPosition].code,
            red = redSeek.progress,
            green = greenSeek.progress,
            blue = blueSeek.progress
        )
    }

    private fun persistDialFromEvent(event: DiKeyEvent) {
        if (event !is DiKeyEvent.Encoder) return
        val type = DialDisplayType.fromCode(event.displayType) ?: return
        val left = event.side == "LEFT"
        settings.saveDial(left, type, event.value)
        val uiLeft = dialSideSpinner.selectedItemPosition == 0
        if (uiLeft == left) {
            suppressUiCallbacks = true
            try {
                dialTypeSpinner.setSelection(type.ordinal)
                applyDialValueRange(type, event.value)
            } finally {
                suppressUiCallbacks = false
            }
        }
    }

    private fun applyLed(all: Boolean) {
        if (!requireConnected()) return
        val mode = LedMode.entries[modeSpinner.selectedItemPosition].code
        val r = redSeek.progress
        val g = greenSeek.progress
        val b = blueSeek.progress
        val position = if (all) {
            LedPosition.ALL.code
        } else {
            LedPosition.entries[positionSpinner.selectedItemPosition].code
        }
        settings.saveStripApply(mode, position, r, g, b)
        controller.seedLedMemory(settings.ledRestoreSnapshot())
        val ok = controller.sendLed(mode, position, r, g, b)
        Snackbar.make(
            connStatusText,
            if (ok) R.string.dikey_led_sent else R.string.dikey_led_send_failed,
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun applyBacklight() {
        if (!requireConnected()) return
        val r = redSeek.progress
        val g = greenSeek.progress
        val b = blueSeek.progress
        settings.saveBacklight(r, g, b)
        persistLedFromUi()
        controller.seedLedMemory(settings.ledRestoreSnapshot())
        val ok = controller.sendButtonBacklight(r, g, b)
        Snackbar.make(
            connStatusText,
            if (ok) R.string.dikey_backlight_sent else R.string.dikey_led_send_failed,
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun appendEvent(event: DiKeyEvent) {
        appendLogLine("${event.summary}\n  ${event.hex}")
    }

    private fun appendLogLine(line: String) {
        val stamped = "${timeFmt.format(Date())}  $line"
        while (eventLines.size >= MAX_LOG_LINES) eventLines.removeFirst()
        eventLines.addFirst(stamped)
        eventLogText.text = eventLines.joinToString("\n\n")
    }

    private fun updateColorLabels() {
        val r = redSeek.progress
        val g = greenSeek.progress
        val b = blueSeek.progress
        redLabel.text = getString(R.string.dikey_led_red_fmt, r)
        greenLabel.text = getString(R.string.dikey_led_green_fmt, g)
        blueLabel.text = getString(R.string.dikey_led_blue_fmt, b)
        ledPreview.setBackgroundColor(Color.rgb(r, g, b))
    }

    private fun renderBleScanState(state: BleScanner.State) {
        bleScanStatusText.text = when (state) {
            BleScanner.State.IDLE ->
                if (latestScanCount > 0) {
                    getString(R.string.ble_scan_stopped, latestScanCount)
                } else {
                    getString(R.string.ble_scan_idle)
                }
            BleScanner.State.SCANNING ->
                getString(R.string.ble_scan_scanning, latestScanCount)
            BleScanner.State.NO_ADAPTER -> getString(R.string.ble_scan_no_adapter)
            BleScanner.State.NO_PERMISSION -> getString(R.string.ble_scan_no_permission)
            BleScanner.State.FAILED ->
                getString(R.string.ble_scan_failed, bleScanner.lastScanErrorCode ?: -1)
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        val perms = requiredPermissions()
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestBluetoothPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), PERMISSION_REQUEST)
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }

    companion object {
        private const val MAX_LOG_LINES = 40
        private const val DIKEY_DEVICE_NAME = "DiKey"
        private const val PERMISSION_REQUEST = 1002
        private const val RECONNECT_SCAN_DELAY_MS = 1500L
    }
}
