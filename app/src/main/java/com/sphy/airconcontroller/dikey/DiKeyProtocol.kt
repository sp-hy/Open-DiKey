package com.sphy.airconcontroller.dikey

/**
 * DiKey BLE framing from vendor APK reverse notes (PROTOCOL.md).
 * Host→device uses AA 55; device→host uses AA 66.
 */
object DiKeyProtocol {
    const val SERVICE_UUID = "0000FF10-0000-1000-8000-00805F9B34FB"
    const val WRITE_UUID = "0000FF11-0000-1000-8000-00805F9B34FB"
    const val NOTIFY_UUID = "0000FF12-0000-1000-8000-00805F9B34FB"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val CMD_DIAL_RIGHT: Byte = 0x01
    const val CMD_DIAL_LEFT: Byte = 0x02
    const val CMD_LED_STRIP: Byte = 0x03
    const val CMD_BUTTON_BACKLIGHT: Byte = 0x05
    const val CMD_ENCODER_MODE_RIGHT: Byte = 0x06
    const val CMD_ENCODER_MODE_LEFT: Byte = 0x07
    const val CMD_ENCODER_RANGE: Byte = 0x08
    const val CMD_BUTTON_EVENT: Byte = 0x10
    const val CMD_ENCODER_EVENT: Byte = 0x11

    fun buildFrame(command: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= 240) { "payload too long" }
        val out = ByteArray(payload.size + 5)
        out[0] = 0xAA.toByte()
        out[1] = 0x55
        out[2] = (payload.size + 4).toByte()
        out[3] = command
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, 4, payload.size)
        }
        var sum = 0
        for (i in 0 until out.size - 1) {
            sum += out[i].toInt() and 0xFF
        }
        out[out.size - 1] = (sum and 0xFF).toByte()
        return out
    }

    /**
     * CMD 0x01 right / 0x02 left — push a value onto a dial LCD.
     * [switchDisplay] maps to vendor `switchDisplay`: true when changing displayType on
     * that dial, false for value-only refreshes of the same type.
     */
    fun buildDialDisplay(
        left: Boolean,
        displayType: Int,
        value: Int,
        switchDisplay: Boolean = true
    ): ByteArray {
        require(displayType in 1..16) { "displayType 1-16" }
        require(value in 0..255) { "value 0-255" }
        return buildFrame(
            if (left) CMD_DIAL_LEFT else CMD_DIAL_RIGHT,
            byteArrayOf(
                displayType.toByte(),
                value.toByte(),
                if (switchDisplay) 1 else 0
            )
        )
    }

    /**
     * CMD 0x06 (right) / 0x07 (left) — which displayTypes the dial may enter.
     * Payload slots are types 2…16 (vendor `y6.eb.g`).
     */
    fun buildEncoderModeConfig(left: Boolean, allowedTypes: Set<Int>): ByteArray {
        val payload = ByteArray(15)
        for (type in 2..16) {
            payload[type - 2] = if (type in allowedTypes) type.toByte() else 0
        }
        return buildFrame(
            if (left) CMD_ENCODER_MODE_LEFT else CMD_ENCODER_MODE_RIGHT,
            payload
        )
    }

    /**
     * CMD 0x08 — fan/media/nav ranges + temp numeral style.
     * Style: 0=digits, 1=type prefix, 2=degree symbol (vendor default).
     */
    fun buildEncoderRangeConfig(
        mediaMin: Int = 0,
        mediaMax: Int = 39,
        navMin: Int = 0,
        navMax: Int = 10,
        fanMin: Int = 1,
        fanMax: Int = 7,
        tempStyle: Int = 2
    ): ByteArray {
        require(mediaMin in 0..255 && mediaMax in 0..255)
        require(navMin in 0..255 && navMax in 0..255)
        require(fanMin in 0..255 && fanMax in 0..255)
        require(tempStyle in 0..2)
        return buildFrame(
            CMD_ENCODER_RANGE,
            byteArrayOf(
                0x11, 0x21,
                fanMin.toByte(), fanMax.toByte(),
                mediaMin.toByte(), mediaMax.toByte(),
                navMin.toByte(), navMax.toByte(),
                0x01, 0x06,
                tempStyle.toByte()
            )
        )
    }

    /** CMD 0x03 — LED strip. Wire color order is GRB. */
    fun buildLedStrip(mode: Int, position: Int, red: Int, green: Int, blue: Int): ByteArray {
        require(mode in 0..4) { "mode 0-4" }
        require(position in 1..5) { "position 1-5" }
        require(red in 0..255 && green in 0..255 && blue in 0..255)
        return buildFrame(
            CMD_LED_STRIP,
            byteArrayOf(
                mode.toByte(),
                position.toByte(),
                green.toByte(),
                red.toByte(),
                blue.toByte()
            )
        )
    }

    /** CMD 0x05 — key backlight. Wire color order is GRB. */
    fun buildButtonBacklight(red: Int, green: Int, blue: Int): ByteArray {
        require(red in 0..255 && green in 0..255 && blue in 0..255)
        return buildFrame(
            CMD_BUTTON_BACKLIGHT,
            byteArrayOf(green.toByte(), red.toByte(), blue.toByte())
        )
    }

    fun displayTypeLabel(code: Int): String =
        DialDisplayType.entries.find { it.code == code }?.label
            ?: "type=0x%02X".format(code)

    fun parseNotify(data: ByteArray): DiKeyEvent? {
        if (data.size < 5) return null
        if ((data[0].toInt() and 0xFF) != 0xAA || (data[1].toInt() and 0xFF) != 0x66) return null
        var sum = 0
        for (i in 0 until data.size - 1) sum += data[i].toInt() and 0xFF
        if ((sum and 0xFF) != (data[data.size - 1].toInt() and 0xFF)) return null

        return when (data[3]) {
            CMD_BUTTON_EVENT -> {
                if (data.size < 7) return null
                val rawId = data[4].toInt() and 0xFF
                val normalized = when (rawId) {
                    in 16..25 -> rawId - 16
                    in 0..9 -> rawId
                    else -> rawId
                }
                // Vendor isUpAction = (rawId <= 9). Physical: 0–9 = UP face, 16–25 = DOWN face.
                val logicalId = if (normalized in 0..9) 10 - normalized else normalized
                val direction = when (rawId) {
                    in 0..9 -> "UP"
                    in 16..25 -> "DOWN"
                    else -> "?"
                }
                val event = when (data[5].toInt() and 0xFF) {
                    0 -> "CLICK"
                    1 -> "DOUBLE_CLICK"
                    2 -> "LONG_PRESS"
                    else -> "EVENT_${data[5].toInt() and 0xFF}"
                }
                DiKeyEvent.Button(rawId, logicalId, direction, event, data.toHex())
            }
            CMD_ENCODER_EVENT -> {
                // Vendor `he.a(9, …)` — long encoder frame is exactly 9 bytes.
                if (data.size >= 9) {
                    val pos = data[4].toInt() and 0xFF
                    val side = when (pos) {
                        0x20 -> "RIGHT"
                        0x21 -> "LEFT"
                        else -> "POS_0x%02X".format(pos)
                    }
                    val eventCode = data[5].toInt() and 0xFF
                    val event = when (eventCode) {
                        0 -> "SINGLE_CLICK"
                        1 -> "MULTI_CLICK"
                        2 -> "LONG_PRESS"
                        3 -> "ROTATE_RIGHT"
                        4 -> "ROTATE_LEFT"
                        else -> "EVENT_$eventCode"
                    }
                    val displayType = data[6].toInt() and 0xFF
                    val value = data[7].toInt() and 0xFF
                    // Vendor accepts displayType 1..16 inclusive of media(15)/nav(16).
                    if (pos !in 0x20..0x21 || eventCode !in 0..4 || displayType !in 1..16) {
                        DiKeyEvent.Raw(
                            "encoder odd pos=0x%02X ev=%d type=%d val=%d".format(
                                pos, eventCode, displayType, value
                            ),
                            data.toHex()
                        )
                    } else {
                        DiKeyEvent.Encoder(side, event, displayType, value, data.toHex())
                    }
                } else if (data.size >= 6) {
                    DiKeyEvent.Raw("GAME/ACK status=${data[4].toInt() and 0xFF}", data.toHex())
                } else {
                    null
                }
            }
            else -> {
                val cmd = data[3].toInt() and 0xFF
                val status = if (data.size > 4) data[4].toInt() and 0xFF else -1
                DiKeyEvent.Raw("ACK/CMD 0x%02X status=%d".format(cmd, status), data.toHex())
            }
        }
    }

    fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}

sealed class DiKeyEvent {
    abstract val summary: String
    abstract val hex: String

    data class Button(
        val rawId: Int,
        val logicalId: Int,
        val direction: String,
        val event: String,
        override val hex: String
    ) : DiKeyEvent() {
        override val summary: String
            get() = "btn$logicalId $direction · $event (raw=$rawId)"
    }

    data class Encoder(
        val side: String,
        val event: String,
        val displayType: Int,
        val value: Int,
        override val hex: String
    ) : DiKeyEvent() {
        override val summary: String
            get() = "Dial $side · $event · ${DiKeyProtocol.displayTypeLabel(displayType)} = $value"
    }

    data class Raw(override val summary: String, override val hex: String) : DiKeyEvent()
}

enum class LedMode(val code: Int, val label: String) {
    OFF(0, "Off"),
    BLINK(1, "Blink"),
    FLOW(2, "Flow"),
    ON(3, "Solid on"),
    BREATH(4, "Breath")
}

/**
 * CMD 0x03 strip position (vendor `j7.m` + turn/hazard LEFT_RIGHT).
 * Only 1–3 are physical bars; 4 and 5 are multi-zone targets.
 */
enum class LedPosition(val code: Int, val label: String) {
    LEFT(1, "1 · Left bar"),
    CENTER(2, "2 · Middle bar"),
    RIGHT(3, "3 · Right bar"),
    ALL(4, "4 · All bars (1+2+3)"),
    LEFT_RIGHT(5, "5 · Both sides (1+3)")
}

/** Probe UI dial targets. */
enum class DialDisplayType(
    val code: Int,
    val label: String,
    val min: Int,
    val max: Int,
    val defaultValue: Int
) {
    DRIVER_TEMP(0x02, "Driver temp", 16, 32, 24),
    DRIVER_FAN(0x03, "Driver fan", 1, 7, 3),
    PASSENGER_TEMP(0x04, "Passenger temp", 16, 32, 24),
    PASSENGER_FAN(0x05, "Passenger fan", 1, 7, 3),
    MEDIA_VOLUME(0x0F, "Media volume", 0, 39, 10),
    VOLUME(0x10, "Nav volume", 0, 10, 5);

    fun clamp(value: Int): Int = value.coerceIn(min, max)

    companion object {
        val probeTypeCodes: Set<Int> = entries.map { it.code }.toSet()

        fun fromCode(code: Int): DialDisplayType? = entries.find { it.code == code }
    }
}
