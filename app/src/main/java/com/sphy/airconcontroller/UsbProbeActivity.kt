package com.sphy.airconcontroller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.sphy.airconcontroller.usb.UsbDeviceListAdapter
import com.sphy.airconcontroller.usb.UsbHostSerial
import com.sphy.airconcontroller.usb.UsbPermissionReceiver
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lists USB host devices, opens the C6 serial probe, sends PING/ID. */
class UsbProbeActivity : OpenDiKeyActivity() {
    private lateinit var usbManager: UsbManager
    private lateinit var serial: UsbHostSerial
    private lateinit var statusText: TextView
    private lateinit var selectedText: TextView
    private lateinit var logText: TextView
    private lateinit var adapter: UsbDeviceListAdapter

    private val logLines = ArrayDeque<String>(MAX_LOG)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var selected: UsbDevice? = null
    private var waitingPermission = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    appendLog("USB attached")
                    val device = deviceExtra(intent)
                    refreshList(selectLikely = true)
                    if (device != null) {
                        selected = device
                        connectDevice(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val gone = deviceExtra(intent)
                    appendLog("USB detached")
                    if (gone != null && selected?.deviceName == gone.deviceName) {
                        serial.close()
                        selected = null
                        selectedText.text = getString(R.string.usb_none_selected)
                    }
                    refreshList(selectLikely = false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usb_probe)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        statusText = findViewById(R.id.usbStatusText)
        selectedText = findViewById(R.id.usbSelectedText)
        logText = findViewById(R.id.usbLogText)
        adapter = UsbDeviceListAdapter(this)
        findViewById<android.widget.ImageButton>(R.id.usbBackButton).setOnClickListener {
            finish()
        }
        findViewById<ListView>(R.id.usbDeviceList).let { list ->
            list.adapter = adapter
            list.setOnItemClickListener { _, _, position, _ ->
                val item = adapter.getItem(position)
                selected = item.device
                selectedText.text = getString(R.string.usb_selected, item.title, item.subtitle)
            }
        }

        serial = UsbHostSerial(
            usbManager,
            onLine = { line -> appendLog("← $line") },
            onState = { msg ->
                statusText.text = msg
                appendLog(msg)
            }
        )

        findViewById<Button>(R.id.usbRefreshButton).setOnClickListener { refreshList(true) }
        findViewById<Button>(R.id.usbConnectButton).setOnClickListener { connectSelected() }
        findViewById<Button>(R.id.usbDisconnectButton).setOnClickListener {
            serial.close()
            statusText.text = getString(R.string.usb_status_idle)
        }
        findViewById<Button>(R.id.usbPingButton).setOnClickListener { send("PING") }
        findViewById<Button>(R.id.usbIdButton).setOnClickListener { send("ID") }
        findViewById<Button>(R.id.usbClearLogButton).setOnClickListener {
            logLines.clear()
            logText.text = getString(R.string.usb_log_empty)
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        handleLaunchIntent(intent)
        refreshList(selectLikely = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
        refreshList(selectLikely = true)
    }

    override fun onDestroy() {
        waitingPermission = false
        mainHandler.removeCallbacksAndMessages(null)
        unregisterReceiver(usbReceiver)
        serial.close()
        super.onDestroy()
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = deviceExtra(intent) ?: return
                selected = device
                selectedText.text = getString(
                    R.string.usb_selected,
                    device.productName ?: "USB",
                    UsbHostSerial.label(device)
                )
                connectDevice(device)
            }
            UsbPermissionReceiver.ACTION -> {
                waitingPermission = false
                val device = deviceExtra(intent) ?: selected
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                appendLog("USB permission result granted=$granted")
                Log.w(TAG, "activity got permission granted=$granted")
                if (device != null) {
                    openDevice(device)
                } else {
                    statusText.text = getString(R.string.usb_perm_denied)
                }
            }
        }
    }

    private fun refreshList(selectLikely: Boolean) {
        val items = serial.listDevices()
        adapter.submit(items)
        if (!serial.isOpen) {
            statusText.text = if (items.isEmpty()) {
                getString(R.string.usb_none)
            } else {
                getString(R.string.usb_listed, items.size)
            }
        }
        if (selectLikely && selected == null) {
            items.firstOrNull { it.likelyProbe }?.let { hit ->
                selected = hit.device
                selectedText.text = getString(R.string.usb_selected, hit.title, hit.subtitle)
            }
        }
        if (selected == null) {
            selectedText.text = getString(R.string.usb_none_selected)
        }
    }

    private fun connectSelected() {
        val device = selected
        if (device == null) {
            statusText.text = getString(R.string.usb_none_selected)
            return
        }
        connectDevice(device)
    }

    private fun connectDevice(device: UsbDevice) {
        val hasPerm = usbManager.hasPermission(device)
        appendLog("Connect ${UsbHostSerial.label(device)} hasPermission=$hasPerm")
        Log.w(TAG, "connect hasPermission=$hasPerm ${UsbHostSerial.label(device)}")
        if (hasPerm) {
            openDevice(device)
            return
        }
        val err = serial.open(device)
        if (err == null) {
            statusText.text = getString(R.string.usb_connected, UsbHostSerial.label(device))
            send("ID")
            return
        }
        appendLog(err)
        val intent = Intent(this, UsbPermissionReceiver::class.java).apply {
            action = UsbPermissionReceiver.ACTION
        }
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
        waitingPermission = true
        statusText.text = getString(R.string.usb_perm_waiting)
        appendLog("Requesting USB permission…")
        usbManager.requestPermission(device, pi)
        mainHandler.postDelayed({
            if (!waitingPermission || serial.isOpen || isDestroyed) return@postDelayed
            appendLog("No Allow popup — trying open again; unplug/replug if this fails")
            openDevice(device)
        }, PERMISSION_FALLBACK_MS)
    }

    private fun openDevice(device: UsbDevice) {
        val err = serial.open(device)
        if (err != null) {
            statusText.text = err
            appendLog(err)
        } else {
            waitingPermission = false
            statusText.text = getString(R.string.usb_connected, UsbHostSerial.label(device))
            send("ID")
        }
    }

    private fun send(cmd: String) {
        if (!serial.isOpen) {
            statusText.text = getString(R.string.usb_not_connected)
            return
        }
        if (serial.writeLine(cmd)) {
            appendLog("→ $cmd")
        } else {
            appendLog("Write failed: $cmd")
        }
    }

    private fun appendLog(line: String) {
        val stamped = "${timeFmt.format(Date())}  $line"
        while (logLines.size >= MAX_LOG) logLines.removeFirst()
        logLines.addLast(stamped)
        logText.text = logLines.joinToString("\n")
    }

    private fun deviceExtra(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "UsbProbe"
        private const val MAX_LOG = 80
        private const val PERMISSION_FALLBACK_MS = 2500L
    }
}
