package com.sphy.airconcontroller.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * USB host serial for the C6 probe: CDC ACM, Silicon Labs CP210x, or a generic
 * bulk IN/OUT pair (Espressif USB-Serial-JTAG). No kernel driver required.
 */
class UsbHostSerial(
    private val usbManager: UsbManager,
    private val onLine: (String) -> Unit,
    private val onState: (String) -> Unit
) {
    data class ListedDevice(
        val device: UsbDevice,
        val title: String,
        val subtitle: String,
        val likelyProbe: Boolean
    )

    @Volatile
    private var running = false
    private var connection: UsbDeviceConnection? = null
    private var dataInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var reader: Thread? = null
    private val writeLock = Any()
    private val main = Handler(Looper.getMainLooper())

    val isOpen: Boolean get() = running && connection != null

    fun listDevices(): List<ListedDevice> =
        usbManager.deviceList.values
            .map { d ->
                val vid = d.vendorId
                val pid = d.productId
                val name = d.productName?.takeIf { it.isNotBlank() } ?: "USB device"
                val mfg = d.manufacturerName?.takeIf { it.isNotBlank() }
                val likely = isLikelyProbe(d)
                ListedDevice(
                    device = d,
                    title = name,
                    subtitle = buildString {
                        if (mfg != null) append(mfg).append(" · ")
                        append("%04X:%04X".format(vid, pid))
                        append(" · ")
                        append(d.deviceName)
                    },
                    likelyProbe = likely
                )
            }
            .sortedWith(compareByDescending<ListedDevice> { it.likelyProbe }.thenBy { it.title })

    fun open(device: UsbDevice): String? {
        close()
        val hadPerm = usbManager.hasPermission(device)
        val conn = usbManager.openDevice(device)
            ?: return if (!hadPerm) {
                "USB permission not granted"
            } else {
                "openDevice failed (another app may own it)"
            }
        if (!hadPerm) {
            Log.w(TAG, "openDevice succeeded without hasPermission=true")
        }
        val found = findBulkPair(device)
            ?: run {
                conn.close()
                return "No bulk IN/OUT pair on this device"
            }
        if (!conn.claimInterface(found.intf, true)) {
            conn.close()
            return "claimInterface failed"
        }
        connection = conn
        dataInterface = found.intf
        epIn = found.epIn
        epOut = found.epOut
        armPort(conn, device, found.intf)
        running = true
        reader = Thread({ readLoop() }, "odk-usb-read").also { it.start() }
        postState("Open ${label(device)}")
        return null
    }

    fun writeLine(line: String): Boolean {
        val conn = connection
        val out = epOut
        if (!running || conn == null || out == null) return false
        val payload = (line.trim() + "\n").toByteArray(Charsets.UTF_8)
        val written = synchronized(writeLock) {
            conn.bulkTransfer(out, payload, payload.size, WRITE_TIMEOUT_MS)
        }
        return written == payload.size
    }

    fun close() {
        running = false
        reader?.interrupt()
        reader = null
        runCatching {
            dataInterface?.let { connection?.releaseInterface(it) }
        }
        runCatching { connection?.close() }
        connection = null
        dataInterface = null
        epIn = null
        epOut = null
        postState("Disconnected")
    }

    private fun readLoop() {
        val buf = ByteArray(256)
        val acc = StringBuilder()
        while (running) {
            val conn = connection
            val inp = epIn
            if (conn == null || inp == null) break
            val n = try {
                conn.bulkTransfer(inp, buf, buf.size, READ_TIMEOUT_MS)
            } catch (t: Throwable) {
                Log.w(TAG, "bulk IN failed", t)
                break
            }
            if (n > 0) {
                acc.append(String(buf, 0, n, Charsets.UTF_8))
                var nl: Int
                while (acc.indexOf("\n").also { nl = it } >= 0) {
                    val raw = acc.substring(0, nl).trim('\r', ' ')
                    acc.delete(0, nl + 1)
                    if (raw.isNotEmpty()) {
                        val line = raw
                        main.post { onLine(line) }
                    }
                }
                if (acc.length > 1024) acc.clear()
            }
        }
        if (running) {
            main.post { onState("USB read ended") }
        }
    }

    private fun armPort(conn: UsbDeviceConnection, device: UsbDevice, dataIntf: UsbInterface) {
        when {
            device.vendorId == VID_SILABS -> {
                conn.controlTransfer(0x41, 0x00, 0x0001, 0, null, 0, CTRL_TIMEOUT_MS)
            }
            else -> {
                val comm = findCdcCommInterface(device)
                val idx = comm?.id ?: 0
                val coding = byteArrayOf(
                    0x00, 0xC2.toByte(), 0x01, 0x00, // 115200 LE
                    0x00, 0x00, 0x08
                )
                conn.controlTransfer(0x21, 0x20, 0, idx, coding, coding.size, CTRL_TIMEOUT_MS)
                conn.controlTransfer(0x21, 0x22, 0x03, idx, null, 0, CTRL_TIMEOUT_MS)
            }
        }
        Log.i(TAG, "armed if=${dataIntf.id} in=${epIn?.address} out=${epOut?.address}")
    }

    private data class BulkPair(
        val intf: UsbInterface,
        val epIn: UsbEndpoint,
        val epOut: UsbEndpoint
    )

    private fun findBulkPair(device: UsbDevice): BulkPair? {
        var fallback: BulkPair? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            var inp: UsbEndpoint? = null
            var out: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inp = ep
                else out = ep
            }
            if (inp != null && out != null) {
                val pair = BulkPair(intf, inp, out)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) return pair
                if (fallback == null) fallback = pair
            }
        }
        return fallback
    }

    private fun findCdcCommInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_COMM) return intf
        }
        return null
    }

    private fun postState(msg: String) {
        main.post { onState(msg) }
    }

    companion object {
        private const val TAG = "UsbHostSerial"
        private const val VID_ESPRESSIF = 0x303A
        private const val VID_SILABS = 0x10C4
        private const val READ_TIMEOUT_MS = 200
        private const val WRITE_TIMEOUT_MS = 1000
        private const val CTRL_TIMEOUT_MS = 1000

        fun isLikelyProbe(device: UsbDevice): Boolean {
            val name = (device.productName ?: "").uppercase()
            if (name.contains("ODK") || name.contains("ESP32") || name.contains("JTAG")) return true
            return device.vendorId == VID_ESPRESSIF || device.vendorId == VID_SILABS
        }

        fun label(device: UsbDevice): String {
            val n = device.productName?.takeIf { it.isNotBlank() }
            val id = "%04X:%04X".format(device.vendorId, device.productId)
            return if (n != null) "$n ($id)" else id
        }
    }
}
