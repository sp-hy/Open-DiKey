package com.sphy.airconcontroller.dikey

/**
 * Press slots on one physical DiKey button.
 * Wire values match [DiKeyEvent.Button] direction / event strings.
 * [DOWN_CLICK] is climate-only and not remappable.
 */
enum class ButtonMapSlot(
    val direction: String,
    val event: String,
    val titleRes: Int
) {
    DOWN_CLICK("DOWN", "CLICK", com.sphy.airconcontroller.R.string.map_slot_down),
    DOWN_LONG("DOWN", "LONG_PRESS", com.sphy.airconcontroller.R.string.map_slot_down_long),
    UP_CLICK("UP", "CLICK", com.sphy.airconcontroller.R.string.map_slot_up),
    UP_LONG("UP", "LONG_PRESS", com.sphy.airconcontroller.R.string.map_slot_up_long);

    val storageKey: String get() = "${direction}_$event"

    val remappable: Boolean get() = this != DOWN_CLICK

    companion object {
        val ALL: List<ButtonMapSlot> = entries.toList()
        val REMAPPABLE: List<ButtonMapSlot> = entries.filter { it.remappable }

        fun from(direction: String, event: String): ButtonMapSlot? =
            entries.find { it.direction == direction && it.event == event }

        fun fromStorageKey(key: String): ButtonMapSlot? =
            entries.find { it.storageKey == key }
    }
}
