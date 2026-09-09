package com.sphy.airconcontroller.dikey

/** One GATT write in flight. USB bridge and native BLE both implement this. */
interface DiKeyFrameSink {
    fun writeFrame(bytes: ByteArray): Boolean
}

/**
 * DiKey session: write queue, 0x08/0x06/0x07 setup, dial/LED restore.
 * Transport (phone GATT or C6 USB bridge) is a [DiKeyFrameSink].
 */
class DiKeyController(
    private val onStatus: (String) -> Unit,
    private val onEvent: (DiKeyEvent) -> Unit
) {
    private val writeQueue = ArrayDeque<OutboundWrite>()
    private var writeInFlight = false
    private var inFlight: OutboundWrite? = null
    private var sink: DiKeyFrameSink? = null
    private var ready = false

    private var lastDialTypeLeft: Int? = null
    private var lastDialValueLeft: Int? = null
    private var lastDialTypeRight: Int? = null
    private var lastDialValueRight: Int? = null
    private var rangeConfigured = false
    private var modesConfigured = false
    private var pendingRestore: PendingDialRestore? = null
    private var pendingLed: com.sphy.airconcontroller.storage.LedRestoreSnapshot? = null

    val isConnected: Boolean get() = ready

    fun postStatus(msg: String) = onStatus(msg)

    fun attachSink(next: DiKeyFrameSink?) {
        sink = next
    }

    fun onTransportReady() {
        ready = true
        rangeConfigured = false
        modesConfigured = false
        writeInFlight = false
        inFlight = null
        writeQueue.clear()
        lastDialTypeLeft = null
        lastDialValueLeft = null
        lastDialTypeRight = null
        lastDialValueRight = null
        onStatus("Ready — configuring dial modes…")
        configureDialInfrastructure()
    }

    fun onTransportDown(reason: String) {
        ready = false
        writeQueue.clear()
        writeInFlight = false
        inFlight = null
        rangeConfigured = false
        modesConfigured = false
        lastDialTypeLeft = null
        lastDialValueLeft = null
        lastDialTypeRight = null
        lastDialValueRight = null
        onStatus(reason)
    }

    fun ingestNotify(value: ByteArray) {
        if (value.isEmpty()) return
        val event = DiKeyProtocol.parseNotify(value)
            ?: DiKeyEvent.Raw("unparsed", value.toHex())
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
        onEvent(event)
    }

    fun onWriteComplete(success: Boolean) {
        if (!writeInFlight) return
        val done = inFlight
        writeInFlight = false
        inFlight = null
        if (success) {
            done?.onSuccess?.invoke()
        } else {
            onStatus("Write failed · ${done?.logLabel ?: ""}")
        }
        drainWriteQueue()
    }

    fun write(frame: ByteArray): Boolean = enqueue(OutboundWrite(frame))

    fun sendLed(mode: Int, position: Int, red: Int, green: Int, blue: Int): Boolean {
        if (!ready) {
            onStatus("Not ready to write")
            return false
        }
        return enqueue(
            OutboundWrite(
                bytes = DiKeyProtocol.buildLedStrip(mode, position, red, green, blue),
                logLabel = "LED mode=$mode pos=$position rgb=$red,$green,$blue"
            )
        )
    }

    fun sendButtonBacklight(red: Int, green: Int, blue: Int): Boolean {
        return enqueue(
            OutboundWrite(
                bytes = DiKeyProtocol.buildButtonBacklight(red, green, blue),
                logLabel = "key backlight rgb=$red,$green,$blue"
            )
        )
    }

    fun sendDialDisplay(left: Boolean, displayType: Int, value: Int): Boolean {
        if (!ready) {
            onStatus("Not ready to write")
            return false
        }
        enqueueDialInfrastructure(restoreDialsAfter = false)

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

    fun seedDialMemory(
        leftType: Int?,
        leftValue: Int?,
        rightType: Int?,
        rightValue: Int?
    ) {
        if (leftType != null && leftValue != null && rightType != null && rightValue != null) {
            pendingRestore = PendingDialRestore(leftType, leftValue, rightType, rightValue)
            onStatus(
                "Will restore L ${DiKeyProtocol.displayTypeLabel(leftType)}=$leftValue · " +
                    "R ${DiKeyProtocol.displayTypeLabel(rightType)}=$rightValue"
            )
        } else {
            pendingRestore = null
        }
        lastDialTypeLeft = null
        lastDialValueLeft = null
        lastDialTypeRight = null
        lastDialValueRight = null
    }

    fun seedLedMemory(snapshot: com.sphy.airconcontroller.storage.LedRestoreSnapshot) {
        pendingLed = snapshot
        val summary = if (snapshot.bars.isEmpty()) {
            "(no strip bars)"
        } else {
            snapshot.bars.entries.sortedBy { it.key }.joinToString(" · ") { (bar, state) ->
                "bar$bar rgb=${state.color.red},${state.color.green},${state.color.blue}"
            }
        }
        val keys = snapshot.backlight?.let { "rgb=${it.red},${it.green},${it.blue}" } ?: "(unset)"
        onStatus("Will restore LED $summary · keys $keys")
    }

    fun restoreSeededDialsToDevice(): Boolean {
        if (!ready) return false
        val pending = pendingRestore
        val led = pendingLed
        if (pending == null && led == null) {
            onStatus("Ready — listening for button / dial events (no prefs)")
            return false
        }
        if (pending != null) {
            lastDialTypeLeft = null
            lastDialValueLeft = null
            lastDialTypeRight = null
            lastDialValueRight = null
            onStatus(
                "Restoring dials · L ${DiKeyProtocol.displayTypeLabel(pending.leftType)}=${pending.leftValue} · " +
                    "R ${DiKeyProtocol.displayTypeLabel(pending.rightType)}=${pending.rightValue}"
            )
            sendDialDisplay(left = true, displayType = pending.leftType, value = pending.leftValue)
            sendDialDisplay(left = false, displayType = pending.rightType, value = pending.rightValue)
        }
        if (led != null) {
            for (bar in led.bars.keys.sorted()) {
                val state = led.bars[bar] ?: continue
                onStatus(
                    "Restoring LED bar$bar · mode=${state.mode} " +
                        "rgb=${state.color.red},${state.color.green},${state.color.blue}"
                )
                sendLed(state.mode, bar, state.color.red, state.color.green, state.color.blue)
            }
            val bl = led.backlight
            if (bl != null) {
                onStatus("Restoring key backlight · rgb=${bl.red},${bl.green},${bl.blue}")
                sendButtonBacklight(bl.red, bl.green, bl.blue)
            }
        }
        onStatus("Ready — listening for button / dial events")
        return true
    }

    fun configureDialInfrastructure(restoreDialsAfter: Boolean = true): Boolean {
        if (!ready) {
            onStatus("Not ready to write")
            return false
        }
        modesConfigured = false
        enqueueDialInfrastructure(restoreDialsAfter)
        drainWriteQueue()
        return true
    }

    private fun enqueueDialInfrastructure(restoreDialsAfter: Boolean) {
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
                    bytes = DiKeyProtocol.buildEncoderModeConfig(
                        left = false,
                        DialDisplayType.rightCycleTypes
                    ),
                    logLabel = "mode RIGHT 0x06 types=${DialDisplayType.rightCycleTypes}"
                )
            )
            writeQueue.addLast(
                OutboundWrite(
                    bytes = DiKeyProtocol.buildEncoderModeConfig(
                        left = true,
                        DialDisplayType.leftCycleTypes
                    ),
                    onSuccess = {
                        modesConfigured = true
                        if (restoreDialsAfter) {
                            restoreSeededDialsToDevice()
                        } else {
                            onStatus("Ready — listening for button / dial events")
                        }
                    },
                    logLabel = "mode LEFT 0x07 types=${DialDisplayType.leftCycleTypes}"
                )
            )
        } else if (restoreDialsAfter) {
            restoreSeededDialsToDevice()
        }
    }

    private fun enqueue(item: OutboundWrite): Boolean {
        if (!ready || sink == null) {
            onStatus("Not ready to write")
            return false
        }
        writeQueue.addLast(item)
        drainWriteQueue()
        return true
    }

    private fun drainWriteQueue() {
        if (writeInFlight) return
        val link = sink ?: return
        val item = writeQueue.removeFirstOrNull() ?: return
        writeInFlight = true
        inFlight = item
        val frame = item.bytes
        if (!link.writeFrame(frame)) {
            writeInFlight = false
            inFlight = null
            onStatus("Write enqueue failed · ${item.logLabel ?: frame.toHex()}")
            drainWriteQueue()
        } else {
            onStatus("TX · ${item.logLabel ?: frame.toHex()} · ${frame.toHex()}")
        }
    }

    private fun ByteArray.toHex(): String = with(DiKeyProtocol) { toHex() }

    private data class PendingDialRestore(
        val leftType: Int,
        val leftValue: Int,
        val rightType: Int,
        val rightValue: Int
    )

    private data class OutboundWrite(
        val bytes: ByteArray,
        val onSuccess: (() -> Unit)? = null,
        val logLabel: String? = null
    )
}
