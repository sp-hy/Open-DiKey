package com.sphy.airconcontroller.storage

import android.content.ComponentName
import android.content.Intent
import android.net.Uri

fun ButtonAction.RunIntent.toAndroidIntent(): Intent {
    val intent = Intent(action)
    when {
        dataUri.isNotBlank() && mimeType.isNotBlank() ->
            intent.setDataAndType(Uri.parse(dataUri), mimeType)
        dataUri.isNotBlank() -> intent.data = Uri.parse(dataUri)
        mimeType.isNotBlank() -> intent.type = mimeType
    }
    if (packageName.isNotBlank()) intent.setPackage(packageName)
    if (component.isNotBlank()) {
        intent.component = ComponentName.unflattenFromString(component)
            ?: component.split("/", limit = 2).takeIf { it.size == 2 }?.let { parts ->
                val cls = if (parts[1].startsWith(".")) parts[0] + parts[1] else parts[1]
                ComponentName(parts[0], cls)
            }
    }
    if (delivery == ButtonAction.RunIntent.DELIVERY_ACTIVITY) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return intent
}

/** Shell form for `am start` / `am broadcast` when the process cannot start activities. */
fun ButtonAction.RunIntent.toAmCommand(): String {
    val verb = if (delivery == ButtonAction.RunIntent.DELIVERY_BROADCAST) "broadcast" else "start"
    val parts = mutableListOf("am", verb)
    if (action.isNotBlank()) {
        parts += "-a"
        parts += shellQuote(action)
    }
    if (dataUri.isNotBlank()) {
        parts += "-d"
        parts += shellQuote(dataUri)
    }
    if (mimeType.isNotBlank()) {
        parts += "-t"
        parts += shellQuote(mimeType)
    }
    if (component.isNotBlank()) {
        parts += "-n"
        parts += shellQuote(component)
    } else if (packageName.isNotBlank()) {
        parts += "-p"
        parts += shellQuote(packageName)
    }
    if (delivery == ButtonAction.RunIntent.DELIVERY_ACTIVITY) {
        parts += "-f"
        parts += "0x10000000"
    }
    return parts.joinToString(" ")
}

private fun shellQuote(value: String): String =
    if (value.any { it.isWhitespace() || it == '\'' || it == '"' }) {
        "'" + value.replace("'", "'\\''") + "'"
    } else {
        value
    }
