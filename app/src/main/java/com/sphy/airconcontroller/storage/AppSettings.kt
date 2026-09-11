package com.sphy.airconcontroller.storage

import android.content.Context
import com.sphy.airconcontroller.dikey.ButtonMapSlot
import com.sphy.airconcontroller.dikey.DialDisplayType
import com.sphy.airconcontroller.dikey.DialMapSlot
import com.sphy.airconcontroller.dikey.LedMode
import com.sphy.airconcontroller.dikey.LedPosition
import com.sphy.airconcontroller.lighting.LightingPeriod

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

    fun buttonAction(buttonId: Int, slot: ButtonMapSlot): ButtonAction? {
        if (!slot.remappable) {
            clearButtonAction(buttonId, slot)
            return null
        }
        migrateLegacyUpMapping(buttonId)
        migrateLegacyOpenApp(buttonId, slot)
        return ButtonAction.fromJson(prefs.getString(slotActionKey(buttonId, slot), null))
    }

    fun setButtonAction(buttonId: Int, slot: ButtonMapSlot, action: ButtonAction) {
        if (!slot.remappable) return
        migrateLegacyUpMapping(buttonId)
        prefs.edit()
            .putString(slotActionKey(buttonId, slot), action.toJson())
            .remove(slotPkgKey(buttonId, slot))
            .remove(slotLabelKey(buttonId, slot))
            .apply()
    }

    fun clearButtonAction(buttonId: Int, slot: ButtonMapSlot) {
        migrateLegacyUpMapping(buttonId)
        prefs.edit()
            .remove(slotActionKey(buttonId, slot))
            .remove(slotPkgKey(buttonId, slot))
            .remove(slotLabelKey(buttonId, slot))
            .apply()
    }

    /** @deprecated Use [buttonAction]; open-app-only helper for older call sites. */
    fun openApp(buttonId: Int, slot: ButtonMapSlot): ButtonAction.OpenApp? =
        buttonAction(buttonId, slot) as? ButtonAction.OpenApp

    fun setOpenApp(buttonId: Int, slot: ButtonMapSlot, packageName: String, label: String) {
        setButtonAction(buttonId, slot, ButtonAction.OpenApp(packageName, label))
    }

    fun clearOpenApp(buttonId: Int, slot: ButtonMapSlot) = clearButtonAction(buttonId, slot)

    fun dialAction(slot: DialMapSlot): ButtonAction? {
        if (!slot.remappable) {
            clearDialAction(slot)
            return null
        }
        return ButtonAction.fromJson(prefs.getString(dialActionKey(slot), null))
    }

    fun setDialAction(slot: DialMapSlot, action: ButtonAction) {
        if (!slot.remappable) return
        prefs.edit().putString(dialActionKey(slot), action.toJson()).apply()
    }

    fun clearDialAction(slot: DialMapSlot) {
        prefs.edit().remove(dialActionKey(slot)).apply()
    }

    /** Old prefs stored only upward click as `dikey_up_btnN_*`. */
    private fun migrateLegacyUpMapping(buttonId: Int) {
        val legacyPkg = prefs.getString(upPkgKey(buttonId), null)?.takeIf { it.isNotBlank() } ?: return
        val slot = ButtonMapSlot.UP_CLICK
        if (prefs.getString(slotActionKey(buttonId, slot), null).isNullOrBlank() &&
            prefs.getString(slotPkgKey(buttonId, slot), null).isNullOrBlank()
        ) {
            val label = prefs.getString(upLabelKey(buttonId), null)?.takeIf { it.isNotBlank() } ?: legacyPkg
            prefs.edit()
                .putString(slotActionKey(buttonId, slot), ButtonAction.OpenApp(legacyPkg, label).toJson())
                .remove(upPkgKey(buttonId))
                .remove(upLabelKey(buttonId))
                .apply()
        } else {
            prefs.edit().remove(upPkgKey(buttonId)).remove(upLabelKey(buttonId)).apply()
        }
    }

    /** Migrate pre-JSON open-app pkg/label keys into a single action blob. */
    private fun migrateLegacyOpenApp(buttonId: Int, slot: ButtonMapSlot) {
        if (!prefs.getString(slotActionKey(buttonId, slot), null).isNullOrBlank()) return
        val pkg = prefs.getString(slotPkgKey(buttonId, slot), null)?.takeIf { it.isNotBlank() } ?: return
        val label = prefs.getString(slotLabelKey(buttonId, slot), null)?.takeIf { it.isNotBlank() } ?: pkg
        prefs.edit()
            .putString(slotActionKey(buttonId, slot), ButtonAction.OpenApp(pkg, label).toJson())
            .remove(slotPkgKey(buttonId, slot))
            .remove(slotLabelKey(buttonId, slot))
            .apply()
    }

    fun colorForBar(bar: Int): LedRgb =
        editingBar(bar)?.color ?: LedRgb(dikeyLedRed, dikeyLedGreen, dikeyLedBlue).clamped()

    fun modeForBar(bar: Int): Int =
        editingBar(bar)?.mode ?: dikeyLedModeCode

    fun colorForBacklight(): LedRgb {
        val fromProfile = lightingProfile(editingLightingPeriod).backlight
        if (fromProfile != null) return fromProfile
        return if (prefs.getBoolean(KEY_DIKEY_BL_SET, false)) {
            LedRgb(
                prefs.getInt(KEY_DIKEY_BL_R, 0),
                prefs.getInt(KEY_DIKEY_BL_G, 0),
                prefs.getInt(KEY_DIKEY_BL_B, 80)
            ).clamped()
        } else {
            LedRgb(0, 0, 80)
        }
    }

    private fun editingBar(bar: Int): LedBarState? =
        lightingProfile(editingLightingPeriod).bars[bar]

    var editingLightingPeriod: LightingPeriod
        get() = if (prefs.getString(KEY_LIGHTING_EDIT, LightingPeriod.DAY.name) == LightingPeriod.NIGHT.name) {
            LightingPeriod.NIGHT
        } else {
            LightingPeriod.DAY
        }
        set(value) {
            prefs.edit().putString(KEY_LIGHTING_EDIT, value.name).apply()
        }

    /** Profile currently applied on the device (from ambient light). */
    fun liveLightingPeriod(): LightingPeriod =
        com.sphy.airconcontroller.lighting.LightingScheduler.currentPeriod()
            ?: editingLightingPeriod
    fun lightingProfile(period: LightingPeriod): LightingProfile {
        ensureLightingProfiles()
        val key = if (period == LightingPeriod.DAY) KEY_PROFILE_DAY else KEY_PROFILE_NIGHT
        return LightingProfile.fromJson(prefs.getString(key, null))
            ?: LightingProfile.fromLive(ledRestoreSnapshotLiveOnly())
    }

    fun saveLightingProfile(period: LightingPeriod, profile: LightingProfile) {
        val key = if (period == LightingPeriod.DAY) KEY_PROFILE_DAY else KEY_PROFILE_NIGHT
        prefs.edit().putString(key, profile.toJson()).apply()
    }

    fun updateEditingBar(bar: Int, mode: Int, red: Int, green: Int, blue: Int) {
        val period = editingLightingPeriod
        val current = lightingProfile(period)
        val nextBars = current.bars.toMutableMap()
        nextBars[bar] = LedBarState(mode, LedRgb(red, green, blue).clamped())
        saveLightingProfile(period, current.copy(bars = nextBars))
        if (period == liveLightingPeriod()) {
            saveStripApply(mode, bar, red, green, blue)
        }
    }

    fun updateEditingBacklight(red: Int, green: Int, blue: Int) {
        val period = editingLightingPeriod
        val current = lightingProfile(period)
        val color = LedRgb(red, green, blue).clamped()
        saveLightingProfile(period, current.copy(backlight = color))
        if (period == liveLightingPeriod()) {
            saveBacklight(red, green, blue)
        }
    }

    /** Push a stored profile into the live reconnect snapshot. */
    fun applyProfileToLive(period: LightingPeriod) {
        val profile = lightingProfile(period)
        for (bar in 1..3) {
            val state = profile.bars[bar] ?: continue
            putBar(bar, state)
        }
        val bl = profile.backlight
        if (bl != null) {
            saveBacklight(bl.red, bl.green, bl.blue)
        }
    }

    fun ledRestoreSnapshot(): LedRestoreSnapshot {
        ensureLightingProfiles()
        val profile = lightingProfile(liveLightingPeriod())
        return LedRestoreSnapshot(
            bars = profile.bars.filterKeys { it in 1..3 },
            backlight = profile.backlight
        )
    }

    private fun ledRestoreSnapshotLiveOnly(): LedRestoreSnapshot {
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

    private fun ensureLightingProfiles() {
        if (prefs.contains(KEY_PROFILE_DAY) && prefs.contains(KEY_PROFILE_NIGHT)) return
        val edit = prefs.edit()
        if (!prefs.contains(KEY_PROFILE_DAY)) {
            edit.putString(KEY_PROFILE_DAY, LightingProfile.defaultDay().toJson())
        }
        if (!prefs.contains(KEY_PROFILE_NIGHT)) {
            edit.putString(KEY_PROFILE_NIGHT, LightingProfile.defaultNight().toJson())
        }
        edit.apply()
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
    private fun slotActionKey(buttonId: Int, slot: ButtonMapSlot) =
        "dikey_map_btn${buttonId}_${slot.storageKey}_action"

    private fun slotPkgKey(buttonId: Int, slot: ButtonMapSlot) =
        "dikey_map_btn${buttonId}_${slot.storageKey}_pkg"

    private fun slotLabelKey(buttonId: Int, slot: ButtonMapSlot) =
        "dikey_map_btn${buttonId}_${slot.storageKey}_label"

    private fun dialActionKey(slot: DialMapSlot) =
        "dikey_map_dial_${slot.storageKey}_action"

    private fun upPkgKey(buttonId: Int) = "dikey_up_btn${buttonId}_pkg"
    private fun upLabelKey(buttonId: Int) = "dikey_up_btn${buttonId}_label"

    companion object {
        private const val PREFS_NAME = "aircon_settings"
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
        private const val KEY_LIGHTING_EDIT = "lighting_edit_period"
        private const val KEY_PROFILE_DAY = "lighting_profile_day"
        private const val KEY_PROFILE_NIGHT = "lighting_profile_night"
    }
}
