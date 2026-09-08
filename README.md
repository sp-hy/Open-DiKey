# Open DiKey

Open-source controller software for the **DiKey** BYD center-console smart key / ambient knob hardware.

The commercial product (physical keys + vendor Android app) is sold as *BYD Shark 6 DiKey Vehicle Intelligent Keys* on Alibaba:

**[Product listing](https://www.alibaba.com/product-detail/BYD-Shark-6-DiKey-Vehicle-Intelligent_1601923176905.html)**

This repo aims to replace (or coexist with) the closed vendor app (`迪铠智联`) with something auditable, extensible, and community-maintained — running on the car’s **DiLink** head unit and talking to BYD’s local `bydauto` APIs where needed.

> **Not affiliated** with DiKey / 迪铠, BYD, or the Alibaba seller. Hardware is third-party aftermarket. Use at your own risk.

## Status

Early / experimental.

| Area | State |
|------|--------|
| Vendor docs, manuals, install videos | Bundled offline under `assets/` |
| Vendor APK (for study) | Bundled; reverse engineering planned |
| DiLink permission + SDK injection | Working (climate PoC path) |
| Open DiKey key / scene / ambient UI | Not started yet |
| Firmware side-channel (ESP32 BLE) | Optional prototype in `firmware/` |

## Hardware

DiKey is a physical smart-key / ambient-light controller that plugs into supported BYD vehicles (listing examples include Shark 6 and related DiLink setups). The seller ships:

- Physical hardware + install guides
- A proprietary Android APK for the head unit
- Tutorial videos and PDFs

Those materials are mirrored in this repo so the project works fully offline.

## Assets

| Path | Contents |
|------|----------|
| [`assets/Docs/Offline/`](assets/Docs/Offline/) | English offline docs portal — open `index.html` (PDFs + how-to videos, no network) |
| [`assets/Docs/Original/`](assets/Docs/Original/) | Original saved Cli.im / QR landing page dump |
| [`assets/Vendor APK/`](assets/Vendor%20APK/) | Stock vendor app (`迪铠智联` 1.1.4) for analysis |
| [`assets/Instructions/`](assets/Instructions/) | Photos of printed install instructions |

## Goals

1. **Document** how DiKey is installed and used (offline pack above).
2. **Reverse-engineer** the vendor APK to learn BLE / ADB / DiLink integration, scene modes, ambient light, floating window, firmware update, etc.
3. **Implement** an open controller app that drives the same hardware (and related vehicle APIs) without depending on the closed binary.
4. Keep the existing **DiLink climate binding** work as a foundation for local `bydauto` access (permissions, hidden-API exemption, in-memory SDK load).

## Current codebase (climate PoC)

The Android app under `app/` is still the earlier **local climate** proof-of-concept. It runs on the DiLink head unit, injects OEM classes from `com.byd.data.collect`, and drives `BYDAutoAcDevice`. That stack is what Open DiKey will reuse for vehicle API access.

### Requirements

- DiLink 5 head unit (Android 11+, minSdk 30)
- USB debugging enabled
- `com.byd.data.collect` installed on the unit

### How vehicle APIs are reached

`bydauto` is not on the boot classpath. Classes live inside `com.byd.data.collect`. Binding needs `BYDAUTO_*` grants and a hidden-API exemption for `com.ts.*` / `dalvik.system`. The OEM DEX is loaded **in memory only** — never copied to app storage.

1. **`AdbPermissionManager`** — connects to `adbd` on `127.0.0.1:5555` via [dadb](https://github.com/mobile-dev-inc/dadb), `pm grant`s permissions, optionally sets `hidden_api_policy` / exemptions (reapplied after reboot if consent is stored).
2. **`Dilink5SdkInjector`** — finds `com.byd.data.collect`, reads `classes*.dex` into memory (`DexPathList.makeInMemoryDexElements`), appends to this app’s `PathClassLoader`.
3. **`BydAcController`** — reflective get/set on `BYDAutoAcDevice` through a `BydPermissionContext` that treats `BYDAUTO_*` checks as granted.

| Control | API (climate PoC) |
|---------|-------------------|
| On / off | `start` / `stop`, `setAcStartState` |
| Driver / passenger temp | `setAcTemperature` (zone 1 / 2, °C) |
| Fan | `set(1000, 0x1DE0000C, level)` (1–7) |
| Auto | `setAcControlMode` |
| Recirc / fresh | `setAcCycleMode` |
| Front demist | `setAcDefrostState` / `setAcWindMode` |
| Rear window + mirrors | `setElectricDefrostState` / AC rear-defrost |
| Air only | `setAcVentilationState` |
| Max cool | `setAcMaxCoolingState` |

### First run (climate PoC)

1. Enable USB debugging on the head unit.
2. Sideload and launch the app.
3. Accept **Allow USB debugging**.
4. Allow the hidden-API exemption; the app restarts.
5. HVAC controls activate once status shows a bound AC snapshot.

### Source map

| Path | Role |
|------|------|
| `adb/AdbPermissionManager.kt` | Local ADB grants + hidden-API exemption |
| `byd/Dilink5SdkInjector.kt` | In-memory load of `com.byd.data.collect` |
| `byd/BydPermissionContext.kt` | Client-side `BYDAUTO_*` permission wrapper |
| `byd/BydAcController.kt` | Reflective AC get/set |
| `MainActivity.kt` | Setup UI and climate controls |
| `service/AirconForegroundService.kt` | Optional ESP32 BLE listener |
| `firmware/` | Optional Seeed XIAO ESP32-C6 BLE button / dial sketch |

## Roadmap

- [ ] Unpack / analyze vendor APK (`assets/Vendor APK/`)
- [ ] Map DiKey protocol (keys, scenes, ambient, firmware OTA)
- [ ] Open DiKey Android UI for the physical controller
- [ ] Reuse DiLink injection for any vehicle features the hardware triggers
- [ ] Document supported vehicle / DiLink combinations

## Credits

DiLink 5 permission and SDK loading follow **BYD Trip Stats** ([angoikon/byd-trip-stats](https://github.com/angoikon/byd-trip-stats), [DILINK5.md](https://github.com/angoikon/byd-trip-stats/blob/main/docs/DILINK5.md)) by Angelos Oikonomou and contributors (`AdbPermissionManager`, `Dilink5SdkInjector`).

`dadb`: [mobile-dev-inc/dadb](https://github.com/mobile-dev-inc/dadb).

Vendor manuals and videos are redistributed here for offline use with purchased hardware; copyright remains with their respective owners.
