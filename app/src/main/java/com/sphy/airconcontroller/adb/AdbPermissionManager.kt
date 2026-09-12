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
    private const val ADB_PORT = 5555
    private val ADB_HOST_CANDIDATES = listOf("127.0.0.1", "::1")
    private const val KEY_FILE = "adbkey"
    private const val KEY_PUB_FILE = "adbkey.pub"
    private const val PREFS_NAME = "adb_permission_prefs"
    private const val PREF_PERMISSIONS_GRANTED = "permissions_granted_v1"
    private const val PREF_HIDDEN_API_CONSENT = "d5_hidden_api_consent_v1"
    private const val PREF_HIDDEN_API_PROMPTED = "d5_hidden_api_prompted_v1"
    private val EXEMPTION_TOKENS = listOf("Lcom/ts/", "Ldalvik/system/")
    private val adbLock = Any()
    @Volatile private var lastAdbHost: String = "127.0.0.1"

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
        "android.permission.BYDAUTO_SENSOR_COMMON",
        "android.permission.BYDAUTO_SENSOR_GET",
        "android.permission.BYDAUTO_LIGHT_COMMON",
        "android.permission.BYDAUTO_LIGHT_GET",
        "android.permission.BYDAUTO_TYRE_COMMON",
        "android.permission.BYDAUTO_TYRE_GET",
        "android.permission.BYDAUTO_STATISTIC_COMMON",
        "android.permission.BYDAUTO_STATISTIC_GET",
        "android.permission.BYDAUTO_SPEED_COMMON",
        "android.permission.BYDAUTO_SPEED_GET",
        "android.permission.BYDAUTO_GEARBOX_COMMON",
        "android.permission.BYDAUTO_GEARBOX_GET",
        "android.permission.BYDAUTO_ENGINE_COMMON",
        "android.permission.BYDAUTO_ENGINE_GET",
        "android.permission.BYDAUTO_CHARGING_COMMON",
        "android.permission.BYDAUTO_CHARGING_GET",
        "android.permission.BYDAUTO_ENERGY_COMMON",
        "android.permission.BYDAUTO_ENERGY_GET",
        "android.permission.BYDAUTO_INSTRUMENT_COMMON",
        "android.permission.BYDAUTO_INSTRUMENT_GET",
    )

    private val BACKGROUND_LAUNCH_GRANTS = listOf(
        "pm grant \$pkg android.permission.SYSTEM_ALERT_WINDOW",
        "appops set \$pkg SYSTEM_ALERT_WINDOW allow",
        "appops set \$pkg START_ACTIVITIES_FROM_BACKGROUND allow",
        "cmd appops set \$pkg START_ACTIVITIES_FROM_BACKGROUND allow",
        "dumpsys deviceidle whitelist +\$pkg",
        "appops set \$pkg RUN_IN_BACKGROUND allow",
        "appops set \$pkg RUN_ANY_IN_BACKGROUND allow",
        "appops set \$pkg WAKE_LOCK allow",
    )

    /**
     * DiLink ACC / auto-start whitelist patches (trip-stats / Overdrive).
     * Best-effort — firmware builds vary; failures are ignored.
     */
    private fun autostartWhitelistCommands(pkg: String): List<String> = listOf(
        "dumpsys deviceidle whitelist +$pkg",
        "appops set $pkg RUN_IN_BACKGROUND allow",
        "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
        "cmd appops set $pkg RUN_IN_BACKGROUND allow",
        "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
        // Merge into BYD SSC / ACC whitelists when present.
        "settings get global ssc_whitelist",
        // Append pkg if missing (shell one-liner; no-op on stock Android).
        "sh -c 'w=\$(settings get global ssc_whitelist 2>/dev/null); case \"\$w\" in *$pkg*) ;; *) settings put global ssc_whitelist \"\${w:+\$w,}$pkg\";; esac'",
        "sh -c 'w=\$(settings get secure ssc_whitelist 2>/dev/null); case \"\$w\" in *$pkg*) ;; *) settings put secure ssc_whitelist \"\${w:+\$w,}$pkg\";; esac'",
        // persist.sys.acc.whitelist is merged in ensureAutostartWhitelist (explicit setprop).
        "service call accmodemanager 1 s16 '$pkg'",
        "content call --uri content://com.byd.appstartup/whitelist --method add --arg $pkg",
        // Ensure our listener can be started even if broadcasts were skipped.
        "am start -n $pkg/com.sphy.airconcontroller.boot.DiKeyWakeActivity --activity-no-animation",
        "am start-foreground-service -n $pkg/com.sphy.airconcontroller.boot.DiKeyListenService",
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
            Log.i(TAG, "Attempting ADB connection to $lastAdbHost:$ADB_PORT")

            val dadb = tryConnect(keyPair, timeoutMs = 3_000)
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
        shellSync(context, command)
    }

    /**
     * Blocking shell for lightweight telemetry (e.g. `/proc/stat`). Uses a short connect
     * timeout so a missing ADB does not stall the vehicle-info poll loop.
     */
    fun runShellCommandQuick(
        context: Context,
        command: String,
        connectTimeoutMs: Long = 400L,
    ): ShellResult {
        val safeCommand = command.trim()
        if (safeCommand.isBlank()) return ShellResult(-1, "No command entered")
        if (!isPortOpen()) return ShellResult(-1, "Local ADB port 5555 is not reachable")
        val keyPair = getOrCreateKeyPair(context)
        val dadb = tryConnect(keyPair, timeoutMs = connectTimeoutMs.coerceAtLeast(800L))
            ?: return ShellResult(-1, "ADB is not authorized yet")
        return try {
            val result = dadb.shell(safeCommand)
            ShellResult(result.exitCode, result.allOutput.trim())
        } catch (e: Exception) {
            ShellResult(-1, "Command failed: ${e.message}")
        } finally {
            runCatching { dadb.close() }
        }
    }

    /**
     * Start a launcher activity as shell so it works while Open DiKey is in the background.
     * [android.app.Activity.startActivity] from a cached process is dropped by Android.
     */
    fun launchComponent(context: Context, packageName: String, component: String?): Boolean {
        val cmd = if (!component.isNullOrBlank()) {
            "am start -n $component -f 0x10000000"
        } else {
            "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        }
        return runAmCommand(context, cmd)
    }

    /** Run an `am start` / `am broadcast` (or other) shell command via local ADB. */
    fun runAmCommand(context: Context, command: String): Boolean {
        val result = shellSync(context, command)
        val out = result.output
        return result.exitCode == 0 ||
            out.contains("Starting", ignoreCase = true) ||
            out.contains("Broadcasting", ignoreCase = true) ||
            out.contains("Events injected", ignoreCase = true)
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
        BACKGROUND_LAUNCH_GRANTS.forEach { cmd ->
            runCatching {
                val r = dadb.shell(cmd.replace("\$pkg", pkg))
                if (r.allOutput.isNotBlank()) Log.d(TAG, "bg-launch: $cmd -> ${r.allOutput.trim()}")
            }
        }
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

    /** Refresh DiLink ACC / auto-start whitelists so the listener wakes without opening the UI. */
    suspend fun ensureAutostartWhitelist(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isPortOpen()) return@withContext false
        val pkg = context.packageName
        val results = runShellBatch(context, autostartWhitelistCommands(pkg))
        val anyOk = results.any { it.exitCode == 0 }

        // Nested sh/setprop in the batch is fragile — merge ACC whitelist explicitly.
        // Overdrive stays up across reboot because it is on this prop; we must be too.
        val before = shellSync(context, "getprop persist.sys.acc.whitelist").output.trim()
        if (!before.contains(pkg)) {
            val next = when {
                before.isBlank() || before.equals("null", ignoreCase = true) -> pkg
                else -> "$before,$pkg"
            }
            val put = shellSync(context, "setprop persist.sys.acc.whitelist $next")
            val after = shellSync(context, "getprop persist.sys.acc.whitelist").output.trim()
            Log.w(TAG, "persist.sys.acc.whitelist '$before' -> '$after' (exit=${put.exitCode})")
        } else {
            Log.i(TAG, "persist.sys.acc.whitelist already has $pkg ($before)")
        }

        Log.i(TAG, "autostart whitelist refresh: ${results.size} cmds, anyOk=$anyOk")
        anyOk || shellSync(context, "getprop persist.sys.acc.whitelist").output.contains(pkg)
    }

    /**
     * Launch / keep the shell-uid ACC daemon that survives DiLink force-stop.
     *
     * Important: never `pkill -f DiKeyAccDaemon` from an inline `sh -c` — that pattern
     * matches the starter shell itself and aborts before app_process starts.
     *
     * @param forceRestart kill + relaunch (e.g. after [Intent.ACTION_MY_PACKAGE_REPLACED])
     */
    suspend fun ensureAccDaemon(
        context: Context,
        forceRestart: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isPortOpen()) {
            Log.w(TAG, "ACC daemon: port $lastAdbHost:$ADB_PORT not open")
            return@withContext false
        }
        val pkg = context.packageName
        if (!forceRestart && isAccDaemonRunning(context)) {
            Log.w(TAG, "ACC daemon already running")
            return@withContext true
        }

        val ping = shellSync(context, "echo dikey-ok")
        if (!ping.output.contains("dikey-ok")) {
            Log.w(TAG, "ACC daemon: ADB shell not authorized (${ping.output.take(120)}) — re-running setup")
            // Stale "setup complete" after reboot / key revoke — force grant path again.
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_PERMISSIONS_GRANTED, false).apply()
            runSetup(context)
            val ping2 = shellSync(context, "echo dikey-ok")
            if (!ping2.output.contains("dikey-ok")) {
                Log.w(TAG, "ACC daemon: ADB still unauthorized after setup")
                return@withContext false
            }
        }

        val apkPath = shellSync(context, "pm path $pkg").output
            .lineSequence()
            .map { it.removePrefix("package:").trim() }
            .firstOrNull { it.endsWith(".apk") }
            ?: return@withContext false.also {
                Log.w(TAG, "ACC daemon: pm path failed")
            }

        // Always rewrite the start script — APK path changes on every install/update.
        // Script file keeps the class name out of the launcher cmdline (avoids self-pkill).
        val script = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("APK='$apkPath'")
            appendLine("LOG=/sdcard/dikey-acc-daemon.log")
            appendLine("MAIN=com.sphy.airconcontroller.daemon.DiKeyAccDaemon")
            appendLine("for pid in \$(ls /proc 2>/dev/null); do")
            appendLine("  case \"\$pid\" in *[!0-9]*|\"\") continue ;; esac")
            appendLine("  cmd=\$(tr '\\0' ' ' < /proc/\$pid/cmdline 2>/dev/null) || continue")
            appendLine("  case \"\$cmd\" in")
            appendLine("    *app_process*\"\$MAIN\"*) kill \"\$pid\" 2>/dev/null ;;")
            appendLine("  esac")
            appendLine("done")
            appendLine("sleep 0.2")
            appendLine("rm -f \"\$LOG\"")
            appendLine(
                "nohup env CLASSPATH=\"\$APK\" /system/bin/app_process64 /system/bin \"\$MAIN\" " +
                    ">\"\$LOG\" 2>&1 </dev/null &"
            )
            appendLine("echo \$!")
        }
        val b64 = android.util.Base64.encodeToString(
            script.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val result = shellSync(
            context,
            "printf '%s' '$b64' | base64 -d > /sdcard/dikey-start-daemon.sh && " +
                "chmod 755 /sdcard/dikey-start-daemon.sh && " +
                "sh /sdcard/dikey-start-daemon.sh",
        )
        Log.w(TAG, "ACC daemon start: exit=${result.exitCode} out=${result.output.take(120)}")
        delay(1_200)
        val running = isAccDaemonRunning(context)
        Log.w(TAG, "ACC daemon running=$running")
        running
    }

    private fun isAccDaemonRunning(context: Context): Boolean {
        val ps = shellSync(context, "ps -A -f").output
        return ps.contains("com.sphy.airconcontroller.daemon.DiKeyAccDaemon")
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

            // Now that WRITE_SECURE_SETTINGS is granted, pin both ADB modes on.
            AdbKeepAlive.ensure(context, "post-grant", viaShell = true)

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
        for (host in adbHosts()) {
            val dadb = tryConnectHost(host, keyPair, timeoutMs) ?: continue
            lastAdbHost = host
            return dadb
        }
        return null
    }

    private fun tryConnectHost(host: String, keyPair: AdbKeyPair, timeoutMs: Long): Dadb? {
        var result: Dadb? = null
        val thread = Thread {
            try {
                val d = Dadb.create(host, ADB_PORT, keyPair)
                val test = d.shell("echo ok")
                if (test.exitCode == 0) result = d else d.close()
            } catch (_: Exception) {
            }
        }
        thread.start()
        thread.join(timeoutMs)
        if (thread.isAlive) {
            thread.interrupt()
            runCatching { result?.close() }
            return null
        }
        return result
    }

    fun isPortOpen(): Boolean {
        // Prefer last good host, then loopback / link-local candidates.
        val hosts = listOf(lastAdbHost) + adbHosts()
        for (host in hosts.distinct()) {
            repeat(2) {
                if (probePort(host)) {
                    lastAdbHost = host
                    return true
                }
                try {
                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                }
            }
        }
        return false
    }

    private fun probePort(host: String): Boolean = try {
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, ADB_PORT), 400)
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Loopback plus on-device IPv4 (wireless adbd sometimes refuses plain 127.0.0.1). */
    private fun adbHosts(): List<String> {
        val hosts = LinkedHashSet<String>()
        hosts.addAll(ADB_HOST_CANDIDATES)
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return hosts.toList()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress || addr is java.net.Inet6Address) continue
                    hosts.add(addr.hostAddress ?: continue)
                }
            }
        } catch (_: Exception) {
        }
        return hosts.toList()
    }

    /** Blocking shell used by [AdbKeepAlive] when reinforcing setprop. */
    fun runShellCommandBlocking(context: Context, command: String): ShellResult =
        shellSync(context, command)

    private fun shellSync(context: Context, command: String): ShellResult = synchronized(adbLock) {
        val safeCommand = command.trim()
        if (safeCommand.isBlank()) return ShellResult(-1, "No command entered")
        if (!isPortOpen()) return ShellResult(-1, "Local ADB port 5555 is not reachable")
        val keyPair = getOrCreateKeyPair(context)
        val dadb = tryConnect(keyPair, timeoutMs = 3_000)
            ?: return ShellResult(-1, "ADB is not authorized yet")
        return try {
            val result = dadb.shell(safeCommand)
            ShellResult(result.exitCode, result.allOutput.trim())
        } catch (e: Exception) {
            ShellResult(-1, "Command failed: ${e.message}")
        } finally {
            runCatching { dadb.close() }
        }
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
