package com.sphy.airconcontroller.dikey

import com.sphy.airconcontroller.R

/** Printed labels for pushing buttons 1–10 downwards. */
object DiKeyButtonCatalog {
    val ids = 1..10

    fun downLabelRes(buttonId: Int): Int = when (buttonId) {
        1 -> R.string.button_down_1
        2 -> R.string.button_down_2
        3 -> R.string.button_down_3
        4 -> R.string.button_down_4
        5 -> R.string.button_down_5
        6 -> R.string.button_down_6
        7 -> R.string.button_down_7
        8 -> R.string.button_down_8
        9 -> R.string.button_down_9
        10 -> R.string.button_down_10
        else -> R.string.button_down_9
    }
}
