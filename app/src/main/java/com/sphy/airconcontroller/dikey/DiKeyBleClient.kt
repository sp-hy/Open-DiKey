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
import com.sphy.airconcontroller.storage.LedRestoreSnapshot
import java.util.ArrayDeque
import java.util.UUID

/**
 * Minimal DiKey GATT client: connect, enable FF12 notify, write FF11 frames.
 */
class DiKeyBleClient(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onEvent: (DiKeyEvent) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    private var ready = false
    private val writeQueue = ArrayDeque<OutboundWrite>()
    private var writeInFlight = false
    private var inFlight: OutboundWrite? = null

    /** Last successfully applied dial LCD type/value per side (vendor switchDisplay tracking). */
    private var lastDialTypeLeft: Int? = null
    private var lastDialValueLeft: Int? = null
    private var lastDialTypeRight: Int? = null
    private var lastDialValueRight: Int? = null
    private var rangeConfigured = false
    private var modesConfigured = false

    /**
     * Survives [close]/[connect] so prefs seeded before connect are not wiped.
     * Live [lastDial*] is cleared on close; this is what reconnect restore uses.
     */
    private var pendingRestore: PendingDialRestore? = null
    private var pendingLed: LedRestoreSnapshot? = null

    private val writeTimeoutRunnable = Runnable {
        if (!writeInFlight) return@Runnable
        postStatus("Write timed out — retrying queue")
        writeInFlight = false
        inFlight = null
        drainWriteQueue()
    }

    val isConnected: Boolean get() = ready

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        close()
        postStatus("Connecting to ${device.address}…")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, callback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        ready = false
        gatt?.disconnect()
        close()
        postStatus("Disconnected")
    }

    @SuppressLint("MissingPermission")
    fun close() {
        ready = false
        writeChar = null
        mainHandler.removeCallbacks(writeTimeoutRunnable)
        writeQueue.clear()
        writeInFlight = false
        inFlight = null
        clearDialState()
        runCatching { gatt?.close() }
        gatt = null
    }

    fun write(frame: ByteArray): Boolean = enqueue(OutboundWrite(frame))

    private fun write(item: OutboundWrite): Boolean = enqueue(item)

    fun sendLed(mode: Int, position: Int, red: Int, green: Int, blue: Int): Boolean {
        if (!ready) {
            postStatus("Not ready to write")
            return false
        }
        return write(
            OutboundWrite(
                bytes = DiKeyProtocol.buildLedStrip(mode, position, red, green, blue),
                logLabel = "LED mode=$mode pos=$position rgb=$red,$green,$blue"
            )
        )
    }

    fun sendButtonBacklight(red: Int, green: Int, blue: Int): Boolean {
        return write(
            OutboundWrite(
                bytes = DiKeyProtocol.buildButtonBacklight(red, green, blue),
                logLabel = "key backlight rgb=$red,$green,$blue"
            )
        )
    }

    /**
     * Push dial LCD after ensuring vendor encoder infrastructure:
     * CMD 0x08 ranges (once) + CMD 0x06/0x07 allow-lists (probe types), then 0x01/0x02.
     *
     * `switchDisplay` is 1 only when that dial's displayType changes.
     */
    fun sendDialDisplay(left: Boolean, displayType: Int, value: Int): Boolean {
        if (!ready) {
            postStatus("Not ready to write")
            return false
        }
        enqueueDialInfrastructure(DialDisplayType.probeTypeCodes, restoreDialsAfter = false)

        val clamped = DialDisplayType.fromCode(displayType)?.clamp(value) ?: value
        val lastType = if (left) lastDialTypeLeft else lastDialTypeRight
        val switchDisplay = lastType == null || lastType != displayType
        val sideLabel = if (left) "LEFT" else "RIGHT"
        writeQueue.addLast(
            OutboundWrite(
                bytes = DiKeyProtocol.buildDialDisplay(left, displayType, clamped, switchDisplay),
                onSuccess = {
                    if (left) {
                        lastDialTypeLeft = displayType
                        lastDialValueLeft = clamped
                    } else {
                        lastDialTypeRight = displayType
                        lastDialValueRight = clamped
                    }
                    // Keep reconnect restore in sync with last successful write.
                    val p = pendingRestore
                    pendingRestore = if (left) {
                        PendingDialRestore(
                            leftType = displayType,
                            leftValue = clamped,
                            rightType = p?.rightType ?: displayType,
                            rightValue = p?.rightValue ?: clamped
                        )
                    } else {
                        PendingDialRestore(
                            leftType = p?.leftType ?: displayType,
                            leftValue = p?.leftValue ?: clamped,
                            rightType = displayType,
                            rightValue = clamped
                        )
                    }
                },
                logLabel = "dial $sideLabel type=0x%02X val=%d switch=%d".format(
                    displayType, clamped, if (switchDisplay) 1 else 0
                )
            )
        )

        if (switchDisplay) {
            val otherLeft = !left
            val otherType = if (otherLeft) lastDialTypeLeft else lastDialTypeRight
            val otherValue = if (otherLeft) lastDialValueLeft else lastDialValueRight
            if (otherType != null && otherValue != null && otherType != displayType) {
                val otherLabel = if (otherLeft) "LEFT" else "RIGHT"
                writeQueue.addLast(
                    OutboundWrite(
                        bytes = DiKeyProtocol.buildDialDisplay(
                            otherLeft, otherType, otherValue, switchDisplay = true
                        ),
                        logLabel = "dial $otherLabel reaffirm type=0x%02X val=%d".format(
                            otherType, otherValue
                        )
                    )
                )
            }
        }
        drainWriteQueue()
        return true
    }

    /**
     * Seed dial restore targets from prefs. Safe to call before [connect] —
     * values are kept across the [close] that connect performs.
     */
    fun seedDialMemory(
        leftType: Int?,
        leftValue: Int?,
        rightType: Int?,
        rightValue: Int?
    ) {
        if (leftType != null && leftValue != null && rightType != null && rightValue != null) {
            pendingRestore = PendingDialRestore(leftType, leftValue, rightType, rightValue)
            postStatus(
                "Will restore L ${DiKeyProtocol.displayTypeLabel(leftType)}=$leftValue · " +
                    "R ${DiKeyProtocol.displayTypeLabel(rightType)}=$rightValue"
            )
        } else {
            pendingRestore = null
        }
        // Session memory starts empty until restore / user apply.
        lastDialTypeLeft = null
        lastDialValueLeft = null
        lastDialTypeRight = null
        lastDialValueRight = null
    }

    fun seedLedMemory(snapshot: LedRestoreSnapshot) {
        pendingLed = snapshot
        val summary = if (snapshot.bars.isEmpty()) {
            "(no strip bars)"
        } else {
            snapshot.bars.entries.sortedBy { it.key }.joinToString(" · ") { (bar, state) ->
                "bar$bar rgb=${state.color.red},${state.color.green},${state.color.blue}"
            }
        }
        val keys = snapshot.backlight?.let { "rgb=${it.red},${it.green},${it.blue}" } ?: "(unset)"
        postStatus("Will restore LED $summary · keys $keys")
    }

    /** After 0x08/0x06/0x07, push dials + all strip bars + key backlight from prefs. */
    fun restoreSeededDialsToDevice(): Boolean {
        if (!ready) return false
        val pending = pendingRestore
        val led = pendingLed
        if (pending == null && led == null) {
            postStatus("Ready — listening for button / dial events (no prefs)")
            return false
        }
        if (pending != null) {
            lastDialTypeLeft = null
            lastDialValueLeft = null
            lastDialTypeRight = null
            lastDialValueRight = null
            postStatus(
                "Restoring dials · L ${DiKeyProtocol.displayTypeLabel(pending.leftType)}=${pending.leftValue} · " +
                    "R ${DiKeyProtocol.displayTypeLabel(pending.rightType)}=${pending.rightValue}"
            )
            sendDialDisplay(left = true, displayType = pending.leftType, value = pending.leftValue)
            sendDialDisplay(left = false, displayType = pending.rightType, value = pending.rightValue)
        }
        if (led != null) {
            for (bar in led.bars.keys.sorted()) {
                val state = led.bars[bar] ?: continue
                postStatus(
                    "Restoring LED bar$bar · mode=${state.mode} " +
                        "rgb=${state.color.red},${state.color.green},${state.color.blue}"
                )
                sendLed(state.mode, bar, state.color.red, state.color.green, state.color.blue)
            }
            val bl = led.backlight
            if (bl != null) {
                postStatus("Restoring key backlight · rgb=${bl.red},${bl.green},${bl.blue}")
                sendButtonBacklight(bl.red, bl.green, bl.blue)
            }
        }
        postStatus("Ready — listening for button / dial events")
        return true
    }

    /** Send 0x08 once + 0x06/0x07 allow-lists so dials accept fan/volume modes and emit rotates. */
    fun configureDialInfrastructure(
        allowedTypes: Set<Int> = DialDisplayType.probeTypeCodes,
        restoreDialsAfter: Boolean = true
    ): Boolean {
        if (!ready) {
            postStatus("Not ready to write")
            return false
        }
        modesConfigured = false
        enqueueDialInfrastructure(allowedTypes, restoreDialsAfter)
        drainWriteQueue()
        return true
    }

    private fun enqueueDialInfrastructure(allowedTypes: Set<Int>, restoreDialsAfter: Boolean) {
        if (!rangeConfigured) {
            writeQueue.addLast(
                OutboundWrite(
                    bytes = DiKeyProtocol.buildEncoderRangeConfig(),
                    onSuccess = { rangeConfigured = true },
                    logLabel = "range 0x08 fan=1-7 media=0-39 vol=0-10"
                )
            )
        }
        if (!modesConfigured) {
            writeQueue.addLast(
                OutboundWrite(
                    bytes = DiKeyProtocol.buildEncoderModeConfig(left = false, allowedTypes),
                    logLabel = "mode RIGHT 0x06 types=$allowedTypes"
                )
            )
            writeQueue.addLast(
                OutboundWrite(
                    bytes = DiKeyProtocol.buildEncoderModeConfig(left = true, allowedTypes),
                    onSuccess = {
                        modesConfigured = true
                        if (restoreDialsAfter) {
                            restoreSeededDialsToDevice()
                        } else {
                            postStatus("Ready — listening for button / dial events")
                        }
                    },
                    logLabel = "mode LEFT 0x07 types=$allowedTypes"
                )
            )
        } else if (restoreDialsAfter) {
            restoreSeededDialsToDevice()
        }
    }

    private fun enqueue(item: OutboundWrite): Boolean {
        if (gatt == null || writeChar == null || !ready) {
            postStatus("Not ready to write")
            return false
        }
        writeQueue.addLast(item)
        drainWriteQueue()
        return true
    }

    private fun clearDialState() {
        lastDialTypeLeft = null
        lastDialValueLeft = null
        lastDialTypeRight = null
        lastDialValueRight = null
        rangeConfigured = false
        modesConfigured = false
        // pendingRestore / pendingLed intentionally kept across connect()/close().
    }

    private data class PendingDialRestore(
        val leftType: Int,
        val leftValue: Int,
        val rightType: Int,
        val rightValue: Int
    )

    @SuppressLint("MissingPermission")
    private fun drainWriteQueue() {
        if (writeInFlight) return
        val g = gatt ?: return
        val ch = writeChar ?: return
        val item = writeQueue.pollFirst() ?: return
        writeInFlight = true
        inFlight = item
        val frame = item.bytes
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, frame, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = writeType
                ch.value = frame
                g.writeCharacteristic(ch)
            }
        }
        if (!ok) {
            writeInFlight = false
            inFlight = null
            postStatus("Write enqueue failed · ${item.logLabel ?: frame.toHex()}")
            mainHandler.post { drainWriteQueue() }
        } else {
            postStatus("TX · ${item.logLabel ?: frame.toHex()} · ${frame.toHex()}")
            mainHandler.removeCallbacks(writeTimeoutRunnable)
            mainHandler.postDelayed(writeTimeoutRunnable, WRITE_TIMEOUT_MS)
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                // No GATT confirm callback — advance after a short settle.
                mainHandler.postDelayed({
                    completeInFlight(success = true)
                }, NO_RESPONSE_GAP_MS)
            }
        }
    }

    private fun completeInFlight(success: Boolean) {
        if (!writeInFlight) return
        mainHandler.removeCallbacks(writeTimeoutRunnable)
        val done = inFlight
        writeInFlight = false
        inFlight = null
        if (success) {
            done?.onSuccess?.invoke()
        }
        drainWriteQueue()
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                ready = false
                postStatus("Connection error $status")
                close()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    postStatus("Connected — discovering services…")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    ready = false
                    writeChar = null
                    postStatus("Disconnected")
                    close()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                postStatus("Service discovery failed ($status)")
                return
            }
            val service = g.getService(UUID.fromString(DiKeyProtocol.SERVICE_UUID))
            if (service == null) {
                postStatus("FF10 service not found (not a DiKey?)")
                return
            }
            val write = service.getCharacteristic(UUID.fromString(DiKeyProtocol.WRITE_UUID))
            val notify = service.getCharacteristic(UUID.fromString(DiKeyProtocol.NOTIFY_UUID))
            if (write == null || notify == null) {
                postStatus("FF11/FF12 characteristics missing")
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
                ready = true
                postStatus("Ready — configuring dial modes…")
                configureDialInfrastructure()
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            ready = status == BluetoothGatt.GATT_SUCCESS
            if (ready) {
                postStatus("Ready — configuring dial modes…")
                configureDialInfrastructure()
            } else {
                postStatus("Notify enable failed ($status)")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleNotify(characteristic.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotify(value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                // Handled by settle delay in drainWriteQueue.
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                postStatus("Write failed ($status)")
                completeInFlight(success = false)
            } else {
                completeInFlight(success = true)
            }
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

    private fun handleNotify(value: ByteArray?) {
        if (value == null || value.isEmpty()) return
        val event = DiKeyProtocol.parseNotify(value)
            ?: DiKeyEvent.Raw("unparsed", value.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
        if (event is DiKeyEvent.Encoder) {
            when (event.side) {
                "LEFT" -> {
                    lastDialTypeLeft = event.displayType
                    lastDialValueLeft = event.value
                }
                "RIGHT" -> {
                    lastDialTypeRight = event.displayType
                    lastDialValueRight = event.value
                }
            }
        }
        mainHandler.post { onEvent(event) }
    }

    private fun postStatus(msg: String) {
        mainHandler.post { onStatus(msg) }
    }

    private fun ByteArray.toHex(): String = with(DiKeyProtocol) { toHex() }

    private data class OutboundWrite(
        val bytes: ByteArray,
        val onSuccess: (() -> Unit)? = null,
        val logLabel: String? = null
    )

    companion object {
        private const val WRITE_TIMEOUT_MS = 2500L
        private const val NO_RESPONSE_GAP_MS = 40L
    }
}
