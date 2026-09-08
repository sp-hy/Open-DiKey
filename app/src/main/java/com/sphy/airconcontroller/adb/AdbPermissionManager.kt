package com.sphy.airconcontroller.adb

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Socket

/**
 * Local ADB helper copied from the trip-stats DiLink-5 approach: the app talks to
 * `adbd` on 127.0.0.1:5555, grants itself BYD vehicle permissions, and (with consent)
 * relaxes hidden-API enforcement so the OEM `bydauto` SDK can bind.
 */
object AdbPermissionManager {

    private const val TAG = "AdbPermissionManager"
    private const val ADB_HOST = "127.0.0.1"
    private const val ADB_PORT = 5555
    private const val KEY_FILE = "adbkey"
    private const val KEY_PUB_FILE = "adbkey.pub"
    private const val PREFS_NAME = "adb_permission_prefs"
    private const val PREF_PERMISSIONS_GRANTED = "permissions_granted_v1"
    private const val PREF_HIDDEN_API_CONSENT = "d5_hidden_api_consent_v1"
    private const val PREF_HIDDEN_API_PROMPTED = "d5_hidden_api_prompted_v1"
    private val EXEMPTION_TOKENS = listOf("Lcom/ts/", "Ldalvik/system/")

    private val REQUIRED_PERMISSIONS = listOf(
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.READ_LOGS",
    )

    private val VEHICLE_API_SETTINGS = listOf(
        "settings put global hidden_api_policy 1",
        "settings put global hidden_api_blacklist_exemptions 'Lcom/ts/,Ldalvik/system/'",
    )

    private val BYDAUTO_PERMISSIONS = listOf(
        "android.permission.BYDAUTO_AC_COMMON",
        "android.permission.BYDAUTO_AC_GET",
        "android.permission.BYDAUTO_AC_SET",
        "android.permission.BYDAUTO_SETTING_COMMON",
        "android.permission.BYDAUTO_SETTING_GET",
        "android.permission.BYDAUTO_SETTING_SET",
        "android.permission.BYDAUTO_BODYWORK_COMMON",
        "android.permission.BYDAUTO_BODYWORK_GET",
        "android.permission.BYDAUTO_BODYWORK_SET",
    )

    sealed class SetupState {
        object Idle : SetupState()
        object Connecting : SetupState()
        object WaitingAuth : SetupState()
        object Granting : SetupState()
        object Done : SetupState()
        data class Failed(val reason: String) : SetupState()
    }

    data class ShellResult(
        val exitCode: Int,
        val output: String
    )

    private val _state = MutableStateFlow<SetupState>(SetupState.Idle)
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun restartApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } ?: return
        val pending = android.app.PendingIntent.getActivity(
            context, 0, launch,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT
        )
        context.getSystemService(android.app.AlarmManager::class.java)
            ?.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 400L, pending)
        Runtime.getRuntime().exit(0)
    }

    fun hasHiddenApiConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_HIDDEN_API_CONSENT, false)

    fun setHiddenApiConsent(context: Context, granted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_HIDDEN_API_CONSENT, granted).apply()
    }

    fun hasBeenPromptedForHiddenApi(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_HIDDEN_API_PROMPTED, false)

    fun markHiddenApiPrompted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_HIDDEN_API_PROMPTED, true).apply()
    }

    fun isSetupComplete(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_PERMISSIONS_GRANTED, false)) return true
        return checkPermissionsGranted(context)
    }

    fun checkPermissionsGranted(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            REQUIRED_PERMISSIONS.all { perm ->
                pm.checkPermission(perm, context.packageName) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun runSetup(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isSetupComplete(context)) {
            _state.value = SetupState.Done
            Log.i(TAG, "Setup already complete")
            return@withContext true
        }

        if (_state.value == SetupState.Connecting ||
            _state.value == SetupState.WaitingAuth ||
            _state.value == SetupState.Granting
        ) {
            Log.d(TAG, "Setup already in progress")
            return@withContext false
        }

        _state.value = SetupState.Connecting

        try {
            if (!isPortOpen()) {
                _state.value = SetupState.Failed(
                    "ADB not enabled. On the car, open Settings → System → Developer Options " +
                        "and enable USB Debugging."
                )
                return@withContext false
            }

            val keyPair = getOrCreateKeyPair(context)
            Log.i(TAG, "Attempting ADB connection to $ADB_HOST:$ADB_PORT")

            val dadb = tryConnect(keyPair, timeoutMs = 2_000)
            if (dadb != null) {
                return@withContext grantPermissionsAndClose(dadb, context)
            }

            _state.value = SetupState.WaitingAuth
            Log.i(TAG, "Waiting for ADB authorization in car UI (max 3 min)...")

            val maxAttempts = 60
            repeat(maxAttempts) { attempt ->
                delay(3_000)
                if (_state.value != SetupState.WaitingAuth) return@withContext false

                Log.d(TAG, "Auth poll ${attempt + 1}/$maxAttempts")
                val d = tryConnect(keyPair, timeoutMs = 2_000)
                if (d != null) {
                    return@withContext grantPermissionsAndClose(d, context)
                }
            }

            _state.value = SetupState.Failed(
                "Authorization timed out. Tap Allow when the USB debugging dialog appears, then retry."
            )
            false
        } catch (e: Exception) {
            Log.e(TAG, "Setup failed: ${e.message}", e)
            _state.value = SetupState.Failed("Connection error: ${e.message}")
            false
        }
    }

    suspend fun runShellCommand(context: Context, command: String): ShellResult = withContext(Dispatchers.IO) {
        val safeCommand = command.trim()
        if (safeCommand.isBlank()) return@withContext ShellResult(-1, "No command entered")

        if (!isPortOpen()) {
            return@withContext ShellResult(-1, "Local ADB port 5555 is not reachable")
        }

        val keyPair = getOrCreateKeyPair(context)
        val dadb = tryConnect(keyPair, timeoutMs = 2_000)
            ?: return@withContext ShellResult(-1, "ADB is not authorized yet")

        try {
            val result = dadb.shell(safeCommand)
            ShellResult(result.exitCode, result.allOutput.trim())
        } catch (e: Exception) {
            ShellResult(-1, "Command failed: ${e.message}")
        } finally {
            runCatching { dadb.close() }
        }
    }

    suspend fun runShellBatch(
        context: Context,
        commands: List<String>,
        perCommandTimeoutMs: Long = 5_000L,
    ): List<ShellResult> = withContext(Dispatchers.IO) {
        if (commands.isEmpty()) return@withContext emptyList()
        if (!isPortOpen()) return@withContext emptyList()

        val keyPair = getOrCreateKeyPair(context)
        val dadb = tryConnect(keyPair, timeoutMs = 2_000) ?: return@withContext emptyList()

        val out = ArrayList<ShellResult>(commands.size)
        try {
            for (cmd in commands) {
                val trimmed = cmd.trim()
                if (trimmed.isBlank()) {
                    out += ShellResult(-1, "")
                    continue
                }
                val result = withTimeoutOrNull(perCommandTimeoutMs) {
                    try {
                        val r = dadb.shell(trimmed)
                        ShellResult(r.exitCode, r.allOutput.trim())
                    } catch (e: Exception) {
                        ShellResult(-1, "Command failed: ${e.message}")
                    }
                } ?: ShellResult(-1, "timeout")
                out += result
            }
        } finally {
            runCatching { dadb.close() }
        }
        out
    }

    private fun applyVehicleApiAccess(dadb: Dadb, pkg: String, hiddenApiConsent: Boolean) {
        BYDAUTO_PERMISSIONS.forEach { perm ->
            runCatching {
                val r = dadb.shell("pm grant $pkg $perm")
                val ok = r.exitCode == 0 || r.allOutput.contains("Success", ignoreCase = true)
                if (!ok && r.allOutput.isNotBlank()) Log.d(TAG, "grant $perm: ${r.allOutput.trim()}")
            }
        }
        if (hiddenApiConsent) applyHiddenApiExemptionIfNeeded(dadb)
        else Log.i(TAG, "hidden-api exemption skipped (no consent)")
    }

    private fun applyHiddenApiExemptionIfNeeded(dadb: Dadb) {
        val current = runCatching {
            dadb.shell("settings get global hidden_api_blacklist_exemptions").allOutput.trim()
        }.getOrNull()
        if (current != null && EXEMPTION_TOKENS.all { current.contains(it) }) {
            Log.i(TAG, "hidden-api exemption already set ('$current') — not re-asserting")
            return
        }
        VEHICLE_API_SETTINGS.forEach { cmd ->
            runCatching {
                val r = dadb.shell(cmd)
                Log.i(TAG, "vehicle-api: $cmd -> exit ${r.exitCode}")
            }.onFailure { Log.w(TAG, "vehicle-api '$cmd' failed: ${it.message}") }
        }
    }

    suspend fun ensureVehicleApiAccess(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isPortOpen()) return@withContext false
        val keyPair = getOrCreateKeyPair(context)
        val dadb = tryConnect(keyPair, timeoutMs = 2_000) ?: return@withContext false
        try {
            applyVehicleApiAccess(dadb, context.packageName, hasHiddenApiConsent(context))
            Log.i(TAG, "DiLink-5 vehicle-API access ensured")
            true
        } catch (e: Exception) {
            Log.w(TAG, "ensureVehicleApiAccess failed: ${e.message}")
            false
        } finally {
            runCatching { dadb.close() }
        }
    }

    private suspend fun grantPermissionsAndClose(dadb: Dadb, context: Context): Boolean {
        return try {
            _state.value = SetupState.Granting
            val pkg = context.packageName
            var allGranted = true

            REQUIRED_PERMISSIONS.forEach { perm ->
                val result = dadb.shell("pm grant $pkg $perm")
                val ok = result.exitCode == 0 || result.allOutput.contains("Success", ignoreCase = true)
                Log.i(TAG, "grant $perm: ${if (ok) "ok" else "fail"} (${result.allOutput.trim()})")
                if (!ok) allGranted = false
            }

            applyVehicleApiAccess(dadb, pkg, hasHiddenApiConsent(context))

            dadb.close()

            if (allGranted) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_PERMISSIONS_GRANTED, true).apply()
                _state.value = SetupState.Done
                Log.i(TAG, "All permissions granted via ADB")
                true
            } else {
                _state.value = SetupState.Failed("Some permissions could not be granted")
                false
            }
        } catch (e: Exception) {
            runCatching { dadb.close() }
            _state.value = SetupState.Failed("Grant failed: ${e.message}")
            false
        }
    }

    private fun tryConnect(keyPair: AdbKeyPair, timeoutMs: Long): Dadb? {
        var result: Dadb? = null
        val thread = Thread {
            try {
                val d = Dadb.create(ADB_HOST, ADB_PORT, keyPair)
                val test = d.shell("echo ok")
                if (test.exitCode == 0) result = d else d.close()
            } catch (_: Exception) {
            }
        }
        thread.start()
        thread.join(timeoutMs)
        if (thread.isAlive) thread.interrupt()
        return result
    }

    fun isPortOpen(): Boolean = try {
        Socket(ADB_HOST, ADB_PORT).use { true }
    } catch (_: Exception) {
        false
    }

    private fun getOrCreateKeyPair(context: Context): AdbKeyPair {
        val privateKey = File(context.filesDir, KEY_FILE)
        val publicKey = File(context.filesDir, KEY_PUB_FILE)
        if (privateKey.exists() && publicKey.exists()) {
            runCatching { return AdbKeyPair.read(privateKey, publicKey) }
        }
        Log.i(TAG, "Generating new ADB key pair")
        AdbKeyPair.generate(privateKey, publicKey)
        return AdbKeyPair.read(privateKey, publicKey)
    }
}
