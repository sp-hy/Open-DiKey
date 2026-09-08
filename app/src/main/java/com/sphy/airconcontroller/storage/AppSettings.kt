package com.sphy.airconcontroller.storage

import android.content.Context
import com.sphy.airconcontroller.dikey.DialDisplayType
import com.sphy.airconcontroller.dikey.LedMode
import com.sphy.airconcontroller.dikey.LedPosition

data class LedRgb(val red: Int, val green: Int, val blue: Int) {
    fun clamped(): LedRgb = LedRgb(
        red.coerceIn(0, 255),
        green.coerceIn(0, 255),
        blue.coerceIn(0, 255)
    )
}

/** One physical strip bar (positions 1–3). */
data class LedBarState(val mode: Int, val color: LedRgb)

/** Full lighting snapshot to re-apply after reconnect. */
data class LedRestoreSnapshot(
    /** Physical bars that have been explicitly saved (keys 1–3). */
    val bars: Map<Int, LedBarState>,
    /** Null = never set key backlight; skip 0x05 on restore. */
    val backlight: LedRgb?
)

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var espDeviceName: String
        get() = prefs.getString(KEY_ESP_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
        set(value) {
            prefs.edit().putString(KEY_ESP_DEVICE_NAME, value).apply()
        }

    var dikeyLastAddress: String?
        get() = prefs.getString(KEY_DIKEY_ADDRESS, null)
        set(value) {
            prefs.edit().putString(KEY_DIKEY_ADDRESS, value).apply()
        }

    /** True = left dial selected in probe UI. */
    var dikeyUiSideLeft: Boolean
        get() = prefs.getBoolean(KEY_DIKEY_UI_LEFT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DIKEY_UI_LEFT, value).apply()
        }

    var dikeyLeftTypeCode: Int
        get() = prefs.getInt(KEY_DIKEY_LEFT_TYPE, DialDisplayType.DRIVER_TEMP.code)
        set(value) {
            prefs.edit().putInt(KEY_DIKEY_LEFT_TYPE, value).apply()
        }

    var dikeyLeftValue: Int
        get() = prefs.getInt(KEY_DIKEY_LEFT_VALUE, DialDisplayType.DRIVER_TEMP.defaultValue)
        set(value) {
            prefs.edit().putInt(KEY_DIKEY_LEFT_VALUE, value).apply()
        }

    var dikeyRightTypeCode: Int
        get() = prefs.getInt(KEY_DIKEY_RIGHT_TYPE, DialDisplayType.PASSENGER_TEMP.code)
        set(value) {
            prefs.edit().putInt(KEY_DIKEY_RIGHT_TYPE, value).apply()
        }

    var dikeyRightValue: Int
        get() = prefs.getInt(KEY_DIKEY_RIGHT_VALUE, DialDisplayType.PASSENGER_TEMP.defaultValue)
        set(value) {
            prefs.edit().putInt(KEY_DIKEY_RIGHT_VALUE, value).apply()
        }

    /** Last UI selection (spinner / seekbars) — not the full restore map. */
    var dikeyLedModeCode: Int
        get() = prefs.getInt(KEY_DIKEY_LED_MODE, LedMode.ON.code)
        set(value) {
            prefs.edit().putInt(KEY_DIKEY_LED_MODE, value).apply()
        }

    var dikeyLedPosition: Int
        get() = prefs.getInt(KEY_DIKEY_LED_POS, LedPosition.LEFT.code).coerceIn(1, 5)
        set(value) {
            prefs.edit().putInt(KEY_DIKEY_LED_POS, value.coerceIn(1, 5)).apply()
        }

    var dikeyLedRed: Int
        get() = prefs.getInt(KEY_DIKEY_LED_R, 255).coerceIn(0, 255)
        set(value) {
            prefs.edit().putInt(KEY_DIKEY_LED_R, value.coerceIn(0, 255)).apply()
        }

    var dikeyLedGreen: Int
        get() = prefs.getInt(KEY_DIKEY_LED_G, 40).coerceIn(0, 255)
        set(value) {
            prefs.edit().putInt(KEY_DIKEY_LED_G, value.coerceIn(0, 255)).apply()
        }

    var dikeyLedBlue: Int
        get() = prefs.getInt(KEY_DIKEY_LED_B, 0).coerceIn(0, 255)
        set(value) {
            prefs.edit().putInt(KEY_DIKEY_LED_B, value.coerceIn(0, 255)).apply()
        }

    fun leftDialType(): DialDisplayType =
        DialDisplayType.fromCode(dikeyLeftTypeCode) ?: DialDisplayType.DRIVER_TEMP

    fun rightDialType(): DialDisplayType =
        DialDisplayType.fromCode(dikeyRightTypeCode) ?: DialDisplayType.PASSENGER_TEMP

    fun saveDial(left: Boolean, type: DialDisplayType, value: Int) {
        val clamped = type.clamp(value)
        if (left) {
            dikeyLeftTypeCode = type.code
            dikeyLeftValue = clamped
        } else {
            dikeyRightTypeCode = type.code
            dikeyRightValue = clamped
        }
    }

    /** Update UI last-used color fields. */
    fun saveLedUi(modeCode: Int, position: Int, red: Int, green: Int, blue: Int) {
        dikeyLedModeCode = modeCode
        dikeyLedPosition = position
        dikeyLedRed = red
        dikeyLedGreen = green
        dikeyLedBlue = blue
    }

    /**
     * Persist strip apply against physical bars.
     * Pos 4 (ALL) → bars 1+2+3; pos 5 (LEFT_RIGHT) → bars 1+3; else single bar 1–3.
     */
    fun saveStripApply(modeCode: Int, position: Int, red: Int, green: Int, blue: Int) {
        val color = LedRgb(red, green, blue).clamped()
        val targets = when (position) {
            LedPosition.ALL.code -> listOf(1, 2, 3)
            LedPosition.LEFT_RIGHT.code -> listOf(1, 3)
            in 1..3 -> listOf(position)
            else -> listOf(position.coerceIn(1, 3))
        }
        for (bar in targets) {
            putBar(bar, LedBarState(modeCode, color))
        }
        saveLedUi(modeCode, position, color.red, color.green, color.blue)
    }

    fun saveBacklight(red: Int, green: Int, blue: Int) {
        val color = LedRgb(red, green, blue).clamped()
        prefs.edit()
            .putInt(KEY_DIKEY_BL_R, color.red)
            .putInt(KEY_DIKEY_BL_G, color.green)
            .putInt(KEY_DIKEY_BL_B, color.blue)
            .putBoolean(KEY_DIKEY_BL_SET, true)
            .apply()
        // Keep UI RGB in sync when adjusting backlight from same sliders.
        dikeyLedRed = color.red
        dikeyLedGreen = color.green
        dikeyLedBlue = color.blue
    }

    fun ledRestoreSnapshot(): LedRestoreSnapshot {
        val bars = linkedMapOf<Int, LedBarState>()
        for (bar in 1..3) {
            getBar(bar)?.let { bars[bar] = it }
        }
        // Migrate pre-per-bar prefs: only the last UI strip target was stored.
        if (bars.isEmpty()) {
            val color = LedRgb(dikeyLedRed, dikeyLedGreen, dikeyLedBlue).clamped()
            val targets = when (dikeyLedPosition) {
                LedPosition.ALL.code -> listOf(1, 2, 3)
                LedPosition.LEFT_RIGHT.code -> listOf(1, 3)
                in 1..3 -> listOf(dikeyLedPosition)
                else -> emptyList()
            }
            for (bar in targets) {
                bars[bar] = LedBarState(dikeyLedModeCode, color)
            }
        }
        val backlight = if (prefs.getBoolean(KEY_DIKEY_BL_SET, false)) {
            LedRgb(
                prefs.getInt(KEY_DIKEY_BL_R, 0),
                prefs.getInt(KEY_DIKEY_BL_G, 0),
                prefs.getInt(KEY_DIKEY_BL_B, 0)
            ).clamped()
        } else {
            null
        }
        return LedRestoreSnapshot(bars, backlight)
    }

    private fun getBar(bar: Int): LedBarState? {
        if (!prefs.contains(barModeKey(bar))) return null
        return LedBarState(
            mode = prefs.getInt(barModeKey(bar), LedMode.ON.code),
            color = LedRgb(
                prefs.getInt(barRKey(bar), 0),
                prefs.getInt(barGKey(bar), 0),
                prefs.getInt(barBKey(bar), 0)
            ).clamped()
        )
    }

    private fun putBar(bar: Int, state: LedBarState) {
        val c = state.color.clamped()
        prefs.edit()
            .putInt(barModeKey(bar), state.mode)
            .putInt(barRKey(bar), c.red)
            .putInt(barGKey(bar), c.green)
            .putInt(barBKey(bar), c.blue)
            .apply()
    }

    private fun barModeKey(bar: Int) = "dikey_led_bar${bar}_mode"
    private fun barRKey(bar: Int) = "dikey_led_bar${bar}_r"
    private fun barGKey(bar: Int) = "dikey_led_bar${bar}_g"
    private fun barBKey(bar: Int) = "dikey_led_bar${bar}_b"

    companion object {
        const val DEFAULT_DEVICE_NAME = "BYD-Aircon"
        private const val PREFS_NAME = "aircon_settings"
        private const val KEY_ESP_DEVICE_NAME = "esp_name"
        private const val KEY_DIKEY_ADDRESS = "dikey_address"
        private const val KEY_DIKEY_UI_LEFT = "dikey_ui_left"
        private const val KEY_DIKEY_LEFT_TYPE = "dikey_left_type"
        private const val KEY_DIKEY_LEFT_VALUE = "dikey_left_value"
        private const val KEY_DIKEY_RIGHT_TYPE = "dikey_right_type"
        private const val KEY_DIKEY_RIGHT_VALUE = "dikey_right_value"
        private const val KEY_DIKEY_LED_MODE = "dikey_led_mode"
        private const val KEY_DIKEY_LED_POS = "dikey_led_pos"
        private const val KEY_DIKEY_LED_R = "dikey_led_r"
        private const val KEY_DIKEY_LED_G = "dikey_led_g"
        private const val KEY_DIKEY_LED_B = "dikey_led_b"
        private const val KEY_DIKEY_BL_SET = "dikey_bl_set"
        private const val KEY_DIKEY_BL_R = "dikey_bl_r"
        private const val KEY_DIKEY_BL_G = "dikey_bl_g"
        private const val KEY_DIKEY_BL_B = "dikey_bl_b"
    }
}
