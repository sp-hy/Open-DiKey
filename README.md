# Open DiKey

Open-source Android app for the **DiKey** aftermarket center-console controller.

Replaces (or coexists with) the vendor app (`迪铠智联`). Runs on DiLink 5 head units and drives climate controls through local BYD APIs.

> **Note:** The commercial DiKey hardware is sold as _BYD Shark 6 DiKey Vehicle Intelligent Keys_ on [Alibaba](https://www.alibaba.com/product-detail/BYD-Shark-6-DiKey-Vehicle-Intelligent_1601923176905.html). Not affiliated with DiKey, BYD, or the seller. Use at your own risk.

<img width="400" alt="image" src="https://github.com/user-attachments/assets/203e3468-4319-4792-8115-004902720a83" /><img width="400" alt="image" src="https://github.com/user-attachments/assets/332d7a0f-0cef-4ad0-888a-090547291e89" />

---

## Installation

### What you need

- DiLink 5 head unit with USB debugging enabled
- **ESP32-C3 Super Mini board** — [AliExpress](https://www.aliexpress.com/item/1005012631245655.html) or [Amazon AU](https://www.amazon.com.au/dp/B0GR475MP4)
- USB cable

### Steps

**1. Flash the USB bridge**

On a PC in Chrome or Edge, download `dikey-usb-bridge-c3.bin` from the [latest release](https://github.com/sp-hy/Open-DiKey/releases) and flash it onto the C3 board using **[ESP Web Flasher](https://esp.huhn.me/)**. If the board doesn't show up, hold **BOOT**, tap **RST**, and try again.

**2. Install the app**

Sideload the APK from the [latest release](https://github.com/sp-hy/Open-DiKey/releases) onto the head unit and launch it **once**.

**3. Grant permissions**

- Accept **Allow USB debugging**
- Allow the hidden-API exemption (app restarts)
- Plug the C3 board into the car's **USB-C port** (USB-A ports may not register)
- If USB permission doesn't pop up, unplug and replug the board while staying in the app
- Allow Open DiKey in any DiLink **auto-start / run in background** menu if available

After that, the listener starts automatically on reboot.

---

## Features

**Colors** — Set ambient bars (left, middle, right) and DiKey backlight with HSV wheels. Changes apply live.

**Button mapping** — Downward presses control climate (as printed). Upward presses can be mapped to open apps.

**Dials** — Left dial = passenger, right dial = driver. Click to switch between **temp** and **fan**, rotate to adjust.

**Auto-start** — A quiet background notification keeps the DiKey working after the UI closes or the car restarts.

---

## Button reference

| Button | Downward press          |
| ------ | ----------------------- |
| 1      | Climate on/off          |
| 2      | A/C compressor          |
| 3      | Cycle wind              |
| 4      | Recirc                  |
| 5      | Auto                    |
| 6      | Front defog             |
| 7      | Rear window / mirrors   |
| 8      | Air only                |
| 9      | Sync _(not mapped yet)_ |
| 10     | Max cool                |

---

## For developers

### Source structure

| Path                           | Purpose             |
| ------------------------------ | ------------------- |
| `MainActivity.kt`              | Home screen         |
| `ColorConfigActivity.kt`       | Ambient + backlight |
| `ButtonMappingActivity.kt`     | Button config       |
| `dikey/DiKeySession.kt`        | USB/BLE session     |
| `dikey/DiKeyClimateMapper.kt`  | Climate controls    |
| `dikey/DiKeyUpMapper.kt`       | App launcher        |
| `usb/DiKeyUsbBridge.kt`        | USB serial          |
| `boot/DiKeyListenService.kt`   | Background listener |
| `adb/AdbPermissionManager.kt`  | Grants + hidden-API |
| `byd/Dilink5SdkInjector.kt`    | OEM SDK loader      |
| `byd/BydAcController.kt`       | Climate API         |
| `firmware/src/DiKeyUsbBridge/` | C3 bridge firmware  |

### Assets

- [`assets/Docs/vendor-re/PROTOCOL.md`](assets/Docs/vendor-re/PROTOCOL.md) — DiKey BLE/USB protocol
- [`assets/Docs/Offline/`](assets/Docs/Offline/) — Offline docs portal
- [`assets/BYD API/`](assets/BYD%20API/) — BYD Auto API V1.0.5

### Roadmap

- [x] Protocol mapping
- [x] Colors + button config
- [x] Climate + dials
- [x] App launcher
- [x] Boot listener
- [ ] More event types
- [ ] Sync button action
- [ ] Vehicle compatibility docs

---

## Credits

DiLink 5 permission and SDK loading follow **[BYD Trip Stats](https://github.com/angoikon/byd-trip-stats)** by Angelos Oikonomou and contributors.

Built with [dadb](https://github.com/mobile-dev-inc/dadb).

Vendor manuals redistributed for offline use with purchased hardware; copyright remains with respective owners.
