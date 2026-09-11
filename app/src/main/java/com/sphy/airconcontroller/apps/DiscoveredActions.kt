package com.sphy.airconcontroller.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import com.sphy.airconcontroller.storage.ButtonAction
import java.util.Locale

/**
 * Friendly actions discovered by querying the device for well-known Intent actions.
 * Android has no “list every Intent” API — we probe a catalog and keep what resolves.
 */
data class DiscoveredAction(
    val label: String,
    val subtitle: String,
    val icon: Drawable?,
    val mapping: ButtonAction.RunIntent
)

private data class ActionProbe(
    val preferredLabel: String,
    val action: String,
    val dataUri: String = "",
    val mimeType: String = "",
    val category: String? = null
)

object DiscoveredActions {
    fun list(context: Context): List<DiscoveredAction> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, DiscoveredAction>()
        for (probe in catalog()) {
            val intent = Intent(probe.action).apply {
                if (probe.dataUri.isNotBlank()) {
                    data = Uri.parse(probe.dataUri)
                }
                if (probe.mimeType.isNotBlank()) {
                    type = probe.mimeType
                }
                probe.category?.let { addCategory(it) }
            }
            @Suppress("DEPRECATION")
            val matches = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            val ri = matches.firstOrNull() ?: continue
            val component = "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
            val key = "${probe.action}|${probe.dataUri}|$component"
            if (found.containsKey(key)) continue
            val handlerLabel = ri.loadLabel(pm).toString()
            val label = probe.preferredLabel.ifBlank { handlerLabel }
            found[key] = DiscoveredAction(
                label = label,
                subtitle = handlerLabel.takeIf { it != label } ?: ri.activityInfo.packageName,
                icon = ri.loadIcon(pm),
                mapping = ButtonAction.RunIntent(
                    label = label,
                    delivery = ButtonAction.RunIntent.DELIVERY_ACTIVITY,
                    action = probe.action,
                    dataUri = probe.dataUri,
                    mimeType = probe.mimeType,
                    packageName = ri.activityInfo.packageName,
                    component = component
                )
            )
        }
        return found.values.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun catalog(): List<ActionProbe> = listOf(
        ActionProbe("Settings", Settings.ACTION_SETTINGS),
        ActionProbe("Wi‑Fi", Settings.ACTION_WIFI_SETTINGS),
        ActionProbe("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
        ActionProbe("Network & internet", Settings.ACTION_WIRELESS_SETTINGS),
        ActionProbe("Mobile network", Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
        ActionProbe("Airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        ActionProbe("Location", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        ActionProbe("Display", Settings.ACTION_DISPLAY_SETTINGS),
        ActionProbe("Sound", Settings.ACTION_SOUND_SETTINGS),
        ActionProbe("Date & time", Settings.ACTION_DATE_SETTINGS),
        ActionProbe("Apps", Settings.ACTION_APPLICATION_SETTINGS),
        ActionProbe("App notifications", Settings.ACTION_APP_NOTIFICATION_SETTINGS),
        ActionProbe("Battery", Settings.ACTION_BATTERY_SAVER_SETTINGS),
        ActionProbe("Storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        ActionProbe("Security", Settings.ACTION_SECURITY_SETTINGS),
        ActionProbe("Privacy", Settings.ACTION_PRIVACY_SETTINGS),
        ActionProbe("Accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        ActionProbe("Language & input", Settings.ACTION_INPUT_METHOD_SETTINGS),
        ActionProbe("NFC", Settings.ACTION_NFC_SETTINGS),
        ActionProbe("Developer options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        ActionProbe("Home app", Settings.ACTION_HOME_SETTINGS),
        ActionProbe("Voice input", Settings.ACTION_VOICE_INPUT_SETTINGS),
        ActionProbe("Search", Intent.ACTION_WEB_SEARCH),
        ActionProbe("Dialer", Intent.ACTION_DIAL, dataUri = "tel:"),
        ActionProbe("Home screen", Intent.ACTION_MAIN, category = Intent.CATEGORY_HOME),
        ActionProbe("All apps", Intent.ACTION_ALL_APPS),
        ActionProbe("Music", Intent.ACTION_MAIN, category = Intent.CATEGORY_APP_MUSIC),
        ActionProbe("Maps", Intent.ACTION_MAIN, category = Intent.CATEGORY_APP_MAPS),
        ActionProbe("Browser", Intent.ACTION_MAIN, category = Intent.CATEGORY_APP_BROWSER),
        ActionProbe("Gallery", Intent.ACTION_MAIN, category = Intent.CATEGORY_APP_GALLERY),
        ActionProbe("Email", Intent.ACTION_MAIN, category = Intent.CATEGORY_APP_EMAIL),
        ActionProbe("Calendar", Intent.ACTION_MAIN, category = Intent.CATEGORY_APP_CALENDAR),
        ActionProbe("Contacts", Intent.ACTION_MAIN, category = Intent.CATEGORY_APP_CONTACTS),
        ActionProbe("Messaging", Intent.ACTION_MAIN, category = Intent.CATEGORY_APP_MESSAGING)
    )
}
