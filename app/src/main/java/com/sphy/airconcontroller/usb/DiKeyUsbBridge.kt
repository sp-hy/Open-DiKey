package com.sphy.airconcontroller.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sphy.airconcontroller.dikey.DiKeyController
import com.sphy.airconcontroller.dikey.DiKeyFrameSink
import com.sphy.airconcontroller.dikey.DiKeyProtocol

/**
 * USB serial link to firmware/src/DiKeyUsbBridge. The C3 owns DiKey GATT;
 * this forwards TX hex and feeds RX notifies into [DiKeyController].
 */
class DiKeyUsbBridge(
    usbManager: UsbManager,
    private val controller: DiKeyController
) : DiKeyFrameSink {
    private val main = Handler(Looper.getMainLooper())
    private val writeTimeout = Runnable {
        Log.w(TAG, "TX timeout")
        controller.onWriteComplete(false)
    }
    private var dikeyReady = false

    val serial = UsbHostSerial(
        usbManager,
        onLine = { line -> handleLine(line) },
        onState = { msg -> controller.postStatus("USB · $msg") }
    )

    val isUsbOpen: Boolean get() = serial.isOpen
    val isDikeyReady: Boolean get() = dikeyReady && serial.isOpen

    fun open(device: UsbDevice): String? {
        dikeyReady = false
        controller.attachSink(this)
        val err = serial.open(device)
        if (err != null) {
            controller.attachSink(null)
            return err
        }
        serial.writeLine("STATUS")
        return null
    }

    fun close() {
        dikeyReady = false
        main.removeCallbacks(writeTimeout)
        serial.close()
        controller.attachSink(null)
    }

    override fun writeFrame(bytes: ByteArray): Boolean {
        if (!serial.isOpen || !dikeyReady) return false
        val hex = with(DiKeyProtocol) { bytes.toCompactHex() }
        val ok = serial.writeLine("TX $hex")
        if (ok) {
            main.removeCallbacks(writeTimeout)
            main.postDelayed(writeTimeout, WRITE_TIMEOUT_MS)
        }
        return ok
    }

    private fun handleLine(line: String) {
        when {
            line.startsWith("RX ", ignoreCase = true) -> {
                val bytes = DiKeyProtocol.parseHex(line.substring(3)) ?: return
                controller.ingestNotify(bytes)
            }
            line.equals("TXOK", ignoreCase = true) -> {
                main.removeCallbacks(writeTimeout)
                controller.onWriteComplete(true)
            }
            line.startsWith("TXERR") -> {
                main.removeCallbacks(writeTimeout)
                controller.onWriteComplete(false)
                controller.postStatus(line)
            }
            line.startsWith("STATE READY") -> {
                dikeyReady = true
                controller.attachSink(this)
                controller.onTransportReady()
            }
            line.startsWith("STATE DISCONNECTED") -> {
                dikeyReady = false
                controller.onTransportDown(line)
            }
            line.startsWith("STATE ") ||
                line.startsWith("LOG ") ||
                line.startsWith("HB ") ||
                line.startsWith("HELLO") ||
                line.startsWith("ID ") ||
                line.equals("PONG", ignoreCase = true) -> {
                controller.postStatus(line)
            }
            else -> Log.d(TAG, line)
        }
    }

    companion object {
        private const val TAG = "DiKeyUsbBridge"
        private const val WRITE_TIMEOUT_MS = 3000L
    }
}
