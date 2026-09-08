package com.sphy.airconcontroller.storage

import android.content.Context
import com.sphy.airconcontroller.dikey.DialDisplayType
import com.sphy.airconcontroller.dikey.LedMode

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

    var dikeyLedModeCode: Int
        get() = prefs.getInt(KEY_DIKEY_LED_MODE, LedMode.ON.code)
        set(value) {
            prefs.edit().putInt(KEY_DIKEY_LED_MODE, value).apply()
        }

    /** 1–5 */
    var dikeyLedPosition: Int
        get() = prefs.getInt(KEY_DIKEY_LED_POS, 1).coerceIn(1, 5)
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

    fun saveLed(modeCode: Int, position: Int, red: Int, green: Int, blue: Int) {
        dikeyLedModeCode = modeCode
        dikeyLedPosition = position
        dikeyLedRed = red
        dikeyLedGreen = green
        dikeyLedBlue = blue
    }

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
    }
}
