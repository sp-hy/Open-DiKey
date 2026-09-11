package com.sphy.airconcontroller.storage

import org.json.JSONObject

/** What a remapped DiKey press does. */
sealed class ButtonAction {
    abstract val label: String

    data class OpenApp(
        val packageName: String,
        override val label: String
    ) : ButtonAction()

    /**
     * Android Intent — apps register for these via intent-filters.
     * [delivery] is [DELIVERY_ACTIVITY] (startActivity) or [DELIVERY_BROADCAST].
     */
    data class RunIntent(
        override val label: String,
        val delivery: String,
        val action: String,
        val dataUri: String = "",
        val mimeType: String = "",
        val packageName: String = "",
        val component: String = ""
    ) : ButtonAction() {
        companion object {
            const val DELIVERY_ACTIVITY = "activity"
            const val DELIVERY_BROADCAST = "broadcast"
        }
    }

    fun toJson(): String {
        val o = JSONObject()
        when (this) {
            is OpenApp -> {
                o.put("type", TYPE_OPEN_APP)
                o.put("packageName", packageName)
                o.put("label", label)
            }
            is RunIntent -> {
                o.put("type", TYPE_INTENT)
                o.put("label", label)
                o.put("delivery", delivery)
                o.put("action", action)
                o.put("dataUri", dataUri)
                o.put("mimeType", mimeType)
                o.put("packageName", packageName)
                o.put("component", component)
            }
        }
        return o.toString()
    }

    companion object {
        private const val TYPE_OPEN_APP = "open_app"
        private const val TYPE_INTENT = "intent"

        fun fromJson(raw: String?): ButtonAction? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                when (o.optString("type")) {
                    TYPE_OPEN_APP -> {
                        val pkg = o.optString("packageName").takeIf { it.isNotBlank() } ?: return null
                        OpenApp(pkg, o.optString("label").ifBlank { pkg })
                    }
                    TYPE_INTENT -> {
                        val action = o.optString("action").takeIf { it.isNotBlank() } ?: return null
                        RunIntent(
                            label = o.optString("label").ifBlank { action },
                            delivery = o.optString("delivery").ifBlank { RunIntent.DELIVERY_ACTIVITY },
                            action = action,
                            dataUri = o.optString("dataUri"),
                            mimeType = o.optString("mimeType"),
                            packageName = o.optString("packageName"),
                            component = o.optString("component")
                        )
                    }
                    else -> null
                }
            }.getOrNull()
        }
    }
}

/** @deprecated Prefer [ButtonAction.OpenApp]; kept for call-site clarity during migration. */
typealias UpOpenApp = ButtonAction.OpenApp
