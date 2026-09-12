# Open DiKey

Open-source Android app for the **DiKey** aftermarket center-console controller.

Replaces the vendor app DiKey Smart Link (`迪铠智联`). Runs on DiLink 5 head units and drives climate controls through local BYD APIs.

> **Note:** The commercial DiKey hardware is sold as _BYD Shark 6 DiKey Vehicle Intelligent Keys_ on [Alibaba](https://www.alibaba.com/product-detail/BYD-Shark-6-DiKey-Vehicle-Intelligent_1601923176905.html). Not affiliated with DiKey, BYD, or the seller. Use at your own risk.

<img width="400" alt="image" src="https://github.com/user-attachments/assets/203e3468-4319-4792-8115-004902720a83" /><img width="400" alt="image" src="https://github.com/user-attachments/assets/38c9f9ac-c14b-47fd-9aca-f03c557aff9c" />
<img width="400" alt="image" src="https://github.com/user-attachments/assets/332d7a0f-0cef-4ad0-888a-090547291e89" /><img width="400" alt="image" src="https://github.com/user-attachments/assets/62957600-bbc8-445e-ad51-03ba85a635f3" />
<img width="400" alt="image" src="https://github.com/user-attachments/assets/3d2ea2da-9456-43e0-b53a-2b65fdb2ad40" />

---

## Compatibility

| Vehicle     | Status    |
| ----------- | --------- |
| BYD Shark 6 | Supported |

Other vehicles are untested.

---

## Installation

### What you need

- DiLink 5 head unit with USB debugging enabled
- **ESP32-C3 Super Mini board** — [AliExpress](https://www.aliexpress.com/item/1005012631245655.html) or [Amazon AU](https://www.amazon.com.au/dp/B0GR475MP4)
- USB cable

### Steps

**1. Flash the USB bridge**

On a PC in Chrome or Edge, download `dikey-usb-bridge-c3.bin` from the [latest release](https://github.com/sp-hy/Open-DiKey/releases), then open **[ESPWebTool](https://esptool.spacehuhn.com/)**:

1. Connect — choose **USB JTAG/serial debug unit**. If it doesn't show up, hold **BOOT** while plugging it in.
2. Delete every existing address slot (there will be about 4)
3. Add a new slot at **`0x0`**
4. Choose `dikey-usb-bridge-c3.bin`

It should look like this before clicking Program:

<img width="400" alt="image" src="https://github.com/user-attachments/assets/1a431a46-d842-4cae-9d60-45c0a3e60526" />

Then click Program.

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

**Colors** — Set ambient bars (left, middle, right) and DiKey backlight with HSV wheels. Pick modes (solid / breath / blink / flow / off). Day and night profiles switch automatically from the vehicle ambient-light sensor (same signal as auto headlights / tunnels).

**Button mapping** — Down click keeps climate (as printed) unless remapped. Down long, up, and up long can open apps or run Android Intents (activity / broadcast).

**Dials** — Left dial = passenger, right dial = driver. Click to switch between **temp** and **fan**, rotate to adjust.

**Auto-start** — A quiet background notification keeps the DiKey working after the UI closes or the car restarts.

**In-app updates** — Settings → Check for updates pulls the latest `open-dikey.apk` from [GitHub Releases](https://github.com/sp-hy/Open-DiKey/releases).

---

## Button reference

| Button | Downward press                |
| ------ | ----------------------------- |
| 1      | Climate on/off                |
| 2      | A/C compressor                |
| 3      | Cycle wind                    |
| 4      | Recirc                        |
| 5      | Auto                          |
| 6      | Front defog                   |
| 7      | Rear window / mirrors         |
| 8      | Air only                      |
| 9      | Sync (driver/passenger temps) |
| 10     | Max cool                      |

---

## For developers

### Release signing

GitHub Actions builds a **signed release** APK (required for in-app updates). Create a keystore once and add **repository secrets**:

```powershell
# Windows
.\scripts\create-release-keystore.ps1
```

```bash
# macOS / Linux
./scripts/create-release-keystore.sh
```

Then in the GitHub repo → **Settings → Secrets and variables → Actions → Repository secrets**, add:

| Secret | Value |
| ------ | ----- |
| `SIGNING_KEYSTORE_BASE64` | Base64 of the `.jks` (printed by the script) |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Usually `open-dikey` |
| `SIGNING_KEY_PASSWORD` | Key password |

Keep the `.jks` and passwords offline — never commit them.

**Migration note:** Older builds were debug-signed (and each CI runner used a different debug key). The first release-signed install may require uninstalling the old APK once; later updates install over the same signing key.

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
- [x] More event types (long-press up/down)
- [x] Sync button action
- [x] Vehicle compatibility docs

---

## Credits

DiLink 5 permission and SDK loading follow **[BYD Trip Stats](https://github.com/angoikon/byd-trip-stats)** by Angelos Oikonomou and contributors.

Built with [dadb](https://github.com/mobile-dev-inc/dadb).

Vendor manuals redistributed for offline use with purchased hardware; copyright remains with respective owners.

### Icons

- [Wheel](https://thenounproject.com/icon/wheel-8052406/) by Asiah from [Noun Project](https://thenounproject.com/) (CC BY)
- [Color wheel](https://thenounproject.com/icon/color-wheel-8403265/) by Fahad Hashmi from [Noun Project](https://thenounproject.com/) (CC BY)
- [Button](https://thenounproject.com/icon/button-8215836/) by Larea from [Noun Project](https://thenounproject.com/) (CC BY)
- [Dial](https://thenounproject.com/icon/dial-4575189/) by Zach Bogart from [Noun Project](https://thenounproject.com/) (CC BY)
