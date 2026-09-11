package com.sphy.airconcontroller.storage

import com.sphy.airconcontroller.dikey.LedMode
import org.json.JSONObject

/** Full ambient + key lighting look for day or night. */
data class LightingProfile(
    val bars: Map<Int, LedBarState>,
    val backlight: LedRgb?
) {
    fun toJson(): String {
        val root = JSONObject()
        val barsJson = JSONObject()
        for ((bar, state) in bars) {
            barsJson.put(
                bar.toString(),
                JSONObject()
                    .put("mode", state.mode)
                    .put("r", state.color.red)
                    .put("g", state.color.green)
                    .put("b", state.color.blue)
            )
        }
        root.put("bars", barsJson)
        if (backlight != null) {
            root.put(
                "backlight",
                JSONObject()
                    .put("r", backlight.red)
                    .put("g", backlight.green)
                    .put("b", backlight.blue)
            )
        } else {
            root.put("backlight", JSONObject.NULL)
        }
        return root.toString()
    }

    companion object {
        fun fromJson(raw: String?): LightingProfile? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val root = JSONObject(raw)
                val barsJson = root.optJSONObject("bars") ?: return null
                val bars = linkedMapOf<Int, LedBarState>()
                for (key in barsJson.keys()) {
                    val bar = key.toIntOrNull() ?: continue
                    val o = barsJson.getJSONObject(key)
                    bars[bar] = LedBarState(
                        mode = o.optInt("mode", LedMode.ON.code),
                        color = LedRgb(o.optInt("r"), o.optInt("g"), o.optInt("b")).clamped()
                    )
                }
                val bl = root.optJSONObject("backlight")
                val backlight = bl?.let {
                    LedRgb(it.optInt("r"), it.optInt("g"), it.optInt("b")).clamped()
                }
                LightingProfile(bars, backlight)
            }.getOrNull()
        }

        fun fromLive(snapshot: LedRestoreSnapshot): LightingProfile {
            val bars = snapshot.bars.ifEmpty {
                defaultDay().bars
            }
            return LightingProfile(bars, snapshot.backlight ?: defaultDay().backlight)
        }

        /** Fresh-install day: solid red at full brightness on bars + keys. */
        fun defaultDay(): LightingProfile {
            val red = LedRgb(255, 0, 0)
            val bar = LedBarState(LedMode.ON.code, red)
            return LightingProfile(
                bars = mapOf(1 to bar, 2 to bar, 3 to bar),
                backlight = red
            )
        }

        /** Fresh-install night: solid blue at half brightness on bars + keys. */
        fun defaultNight(): LightingProfile {
            val blue = LedRgb(0, 0, 128)
            val bar = LedBarState(LedMode.ON.code, blue)
            return LightingProfile(
                bars = mapOf(1 to bar, 2 to bar, 3 to bar),
                backlight = blue
            )
        }
    }
}
