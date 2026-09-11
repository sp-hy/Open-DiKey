package com.sphy.airconcontroller.dikey

/**
 * Press slots on a DiKey dial (encoder).
 * Wire values match [DiKeyEvent.Encoder] side / event strings.
 * Click keeps climate temp/fan toggle; only long-press is remappable.
 */
enum class DialMapSlot(
    val side: String,
    val event: String,
    val titleRes: Int
) {
    LEFT_CLICK("LEFT", "SINGLE_CLICK", com.sphy.airconcontroller.R.string.map_slot_dial_click),
    LEFT_LONG("LEFT", "LONG_PRESS", com.sphy.airconcontroller.R.string.map_slot_dial_long),
    RIGHT_CLICK("RIGHT", "SINGLE_CLICK", com.sphy.airconcontroller.R.string.map_slot_dial_click),
    RIGHT_LONG("RIGHT", "LONG_PRESS", com.sphy.airconcontroller.R.string.map_slot_dial_long);

    val storageKey: String get() = "${side}_$event"

    val remappable: Boolean get() = event == "LONG_PRESS"

    companion object {
        fun remappableForSide(side: String): List<DialMapSlot> =
            entries.filter { it.side.equals(side, ignoreCase = true) && it.remappable }

        fun longForSide(side: String): DialMapSlot =
            if (side.equals("LEFT", ignoreCase = true)) LEFT_LONG else RIGHT_LONG

        fun from(side: String, event: String): DialMapSlot? =
            entries.find { it.side.equals(side, ignoreCase = true) && it.event == event }

        fun fromStorageKey(key: String): DialMapSlot? =
            entries.find { it.storageKey == key }
    }
}
