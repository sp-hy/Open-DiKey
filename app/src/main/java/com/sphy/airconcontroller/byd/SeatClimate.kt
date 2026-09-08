package com.sphy.airconcontroller.byd

enum class SeatPosition {
    DRIVER,
    PASSENGER
}

enum class SeatMode {
    HEAT,
    COOL
}

enum class SeatLevel {
    OFF,
    LOW,
    HIGH;

    fun next(): SeatLevel = when (this) {
        OFF -> LOW
        LOW -> HIGH
        HIGH -> OFF
    }
}
