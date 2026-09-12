# Autostart on DiLink 5 — status and limitations

Open DiKey **does not auto-start** after vehicle deep sleep / cold boot.
Open the app once after power-on; the foreground listener then keeps the
DiKey USB/BLE link alive while the UI is in the background.

This document records what we tried, what worked briefly, and why a reliable
sideloaded solution is not available on current Shark 6 / DiLink 5 firmware.

---

## Current expected behaviour

| Situation | What happens |
| --- | --- |
| User opens Open DiKey | `DiKeyListenService` starts; USB/BLE connect; dials + lighting restore |
| App in background (same session) | Foreground service keeps listening |
| Short ACC cycle while process still alive | Often keeps working |
| Deep sleep / long power-off / cold boot | DiLink force-stops the package (`stopped=true`). Boot/ACC broadcasts are **not** delivered. User must open the app again |

Local ADB setup (grant BYD permissions / hidden-API consent) still runs when
the app is opened. That is **not** used for autostart anymore.

---

## Why DiLink blocks autostart

1. **`stopped=true`** — After force-stop (common on ACC off / deep sleep), Android
   will not deliver `BOOT_COMPLETED`, ACC/IGN, or similar broadcasts to the package
   until something explicitly starts an activity/service (e.g. user opens the app,
   or shell `am start`).

2. **Force-stop persists across reboot** — The flag is not cleared by a head-unit
   restart alone.

3. **Whitelist is not enough** — Putting the package on
   `persist.sys.acc.whitelist` / SSC lists (same family as Overdrive) did **not**
   prevent force-stop after deep sleep. Overdrive was observed in the same
   `stopped=true` / no-process state after a long power-off.

4. **No durable out-of-process helper for a normal APK** — A shell-uid
   `app_process` daemon can clear `stopped=true` **while it is running**, but it
   dies on deep sleep / reboot. Restarting it requires either the app already
   running (chicken-and-egg) or an external `adb shell` / privileged component.

---

## Approaches tried (removed from the tree)

These were prototyped and later stripped when deep sleep proved unreliable:

| Approach | Result |
| --- | --- |
| Boot / ACC / IGN / quickboot receivers + AlarmManager re-kicks | No effect while `stopped=true` |
| `persist.sys.acc.whitelist` + SSC / appstartup / deviceidle grants | Did not stop deep-sleep force-stop |
| Shell-uid ACC daemon (`app_process` + `DiKeyWakeActivity`) | Works **between** force-stops if already launched; dies on deep sleep; launch via local ADB was flaky |
| ADB keep-alive (`adb_enabled` + BYD `wiress` props) | Helps keep `adbd` up; does **not** start the app |
| Local Dadb to relaunch daemon after wake | Often `127.0.0.1:5555` broken pipe / not open under concurrent wireless ADB |

Relevant prior patterns: Overdrive / trip-stats ACC listeners,
[BYD-ADB-Unlock](https://github.com/DottoreTozzi/BYD-ADB-Unlock) for ADB restore only.

---

## What would be needed for a real fix

Any durable solution needs something that **survives deep sleep** and can run
`am start` (or equivalent) when the head unit wakes, for example:

- A privileged / system-signed component DiLink does not force-stop
- OEM auto-start policy that actually exempts the package from force-stop
- An external always-on agent (not a normal sideloaded APK alone)

Until one of those exists, treat “open once after power-on” as the supported model.

---

## Cleanup notes (dev)

If an old debug build left a shell daemon behind:

```text
adb shell pkill -f DiKeyAccDaemon
adb shell rm -f /sdcard/dikey-acc-daemon.log /sdcard/dikey-start-daemon.sh
```

Optional: inspect `getprop persist.sys.acc.whitelist` — leftover entries are
harmless but unused by current builds.

---

## Related code (current)

- `boot/DiKeyListenService.kt` — foreground USB/BLE listener (user-started session)
- `adb/AdbPermissionManager.kt` — one-time / on-open vehicle API grants only
- `OpenDiKeyApp` — starts the listener when the process is created (after user launch)
